(ns axxium.extern.codex-intent-store
  "Linux Node custody for a saved Codex dispatch intent."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [axxium.extern.token-crypto :as token-crypto]
            [clojure.string :as str]))

(def ^:private record-limit 16384)
(def ^:private binding-fields [:actor-did :device-id :thread-id :generation])

(defn- refuse! [reason]
  (throw (ex-info "Codex dispatch intent store refused the operation" {:reason reason})))

(defn- id? [value]
  (and (string? value) (<= (count value) 512) (not (str/blank? value))))

(defn- intent? [{:keys [binding caller-id notice-sha256] :as intent}]
  (and (map? intent)
       (= #{:binding :caller-id :notice-sha256} (set (keys intent)))
       (map? binding)
       (= (set binding-fields) (set (keys binding)))
       (every? id? (vals binding))
       (id? caller-id)
       (string? notice-sha256)
       (boolean (re-matches #"[0-9a-f]{64}" notice-sha256))))

(defn- require-intent! [intent]
  (when-not (and (map? intent) (intent? intent))
    (refuse! :invalid-intent)))

(defn- owned-mode? [stat mode]
  (and (= (.-uid stat) (.geteuid js/process))
       (= mode (bit-and 4095 (.-mode stat)))))

(defn- with-store! [directory operation]
  (when-not (and (= "linux" (.-platform js/process))
                 (string? directory)
                 (path/isAbsolute directory))
    (refuse! :invalid-store))
  (let [root (path/resolve directory)]
    (when-not (= root (fs/realpathSync root))
      (refuse! :invalid-store))
    (let [descriptor (fs/openSync root (bit-or (.-O_RDONLY fs/constants)
                                               (.-O_DIRECTORY fs/constants)
                                               (.-O_NOFOLLOW fs/constants)))]
      (try
        (let [stat (fs/fstatSync descriptor)
              pinned (str "/proc/self/fd/" descriptor)
              reopened-stat (fs/statSync pinned)]
          (when-not (and (.isDirectory stat) (owned-mode? stat 8r700)
                         (= (.-dev stat) (.-dev reopened-stat))
                         (= (.-ino stat) (.-ino reopened-stat)))
            (refuse! :invalid-store))
          (operation descriptor pinned))
        (finally (fs/closeSync descriptor))))))

(defn- record-path [root caller-id phase]
  (path/join root (str (token-crypto/stable-digest "codex-dispatch-intent/v1" caller-id)
                       "." (name phase))))

(defn- encode [phase {:keys [binding caller-id notice-sha256]} queue-id]
  (let [frame [1 (name phase) caller-id (mapv binding binding-fields) notice-sha256 queue-id]]
    (js/Buffer.from (str (js/JSON.stringify (clj->js frame)) "\n") "utf8")))

(defn- decode! [phase caller-id bytes]
  (let [data (try
               (js->clj (js/JSON.parse (.toString bytes "utf8")))
               (catch :default _ (refuse! :invalid-record)))
        frame (when (vector? data) data)
        [version kind saved-caller binding-values notice-sha256 queue-id] frame]
    (when-not (and (= 6 (count frame))
                   (= 1 version) (= (name phase) kind) (= caller-id saved-caller)
                   (vector? binding-values) (= (count binding-fields) (count binding-values))
                   (if (= :accepted phase) (id? queue-id) (nil? queue-id)))
      (refuse! :invalid-record))
    (let [intent {:binding (zipmap binding-fields binding-values)
                  :caller-id saved-caller :notice-sha256 notice-sha256}]
      (when-not (and (intent? intent) (.equals bytes (encode phase intent queue-id)))
        (refuse! :invalid-record))
      {:intent intent :queue-id queue-id})))

(defn- open-record [filename]
  (try
    (fs/openSync filename (bit-or (.-O_RDONLY fs/constants)
                                  (.-O_NOFOLLOW fs/constants)
                                  (.-O_NONBLOCK fs/constants)))
    (catch :default failure
      (when-not (= "ENOENT" (.-code failure))
        (throw failure)))))

(defn- read-bytes! [descriptor]
  (let [stat (fs/fstatSync descriptor)
        bytes (js/Buffer.alloc (inc record-limit))]
    (when-not (and (.isFile stat) (owned-mode? stat 8r600)
                   (<= 1 (.-size stat) record-limit))
      (refuse! :invalid-record))
    (loop [offset 0]
      (when (> offset record-limit)
        (refuse! :invalid-record))
      (let [length (fs/readSync descriptor bytes offset (- (.-length bytes) offset) offset)]
        (if (zero? length)
          (.subarray bytes 0 offset)
          (recur (+ offset length)))))))

(defn- read-record! [root caller-id phase]
  (when-let [descriptor (open-record (record-path root caller-id phase))]
    (try
      (decode! phase caller-id (read-bytes! descriptor))
      (finally (fs/closeSync descriptor)))))

(defn- link-record! [temporary filename]
  (try
    (fs/linkSync temporary filename)
    true
    (catch :default failure
      (if (= "EEXIST" (.-code failure))
        false
        (throw failure)))))

(defn- commit! [descriptor root phase {:keys [caller-id] :as intent} queue-id]
  (let [temporary (path/join root (str ".intent-" (crypto/randomUUID) ".tmp"))
        filename (record-path root caller-id phase)
        bytes (encode phase intent queue-id)
        staging (fs/openSync temporary "wx" 8r600)]
    (try
      (try
        (fs/writeFileSync staging bytes)
        (fs/fsyncSync staging)
        (finally (fs/closeSync staging)))
      (let [published? (link-record! temporary filename)]
        (when (and (not published?)
                   (not= {:intent intent :queue-id queue-id} (read-record! root caller-id phase)))
          (refuse! :record-conflict))
        (fs/fsyncSync descriptor)
        published?)
      (finally (fs/unlinkSync temporary)))))

(defn- load-intent! [root caller-id]
  ;; Observe dependent records first so a concurrent publication can't
  ;; masquerade as an orphan. Earlier records are immutable once linked.
  (let [accepted (read-record! root caller-id :accepted)
        attempted (read-record! root caller-id :attempted)
        prepared (read-record! root caller-id :prepared)
        intent (:intent prepared)]
    (when (or (and attempted (not= intent (:intent attempted)))
              (and accepted (or (not attempted) (not= intent (:intent accepted)))))
      (refuse! :record-conflict))
    (cond
      accepted {:state :accepted :intent (assoc intent :queue-id (:queue-id accepted))}
      attempted {:state :attempted :intent intent}
      prepared {:state :prepared :intent intent})))

(defn- require-saved! [saved intent]
  (when-not (= intent (dissoc (:intent saved) :queue-id))
    (refuse! :intent-mismatch)))

(defn recover!
  "Read one caller's saved intent and its state from the private store."
  [directory caller-id]
  (when-not (id? caller-id) (refuse! :invalid-caller-id))
  (with-store! directory (fn [_ root] (load-intent! root caller-id))))

(defn prepare!
  "Persist the exact intent before submission. An identical retry reads the
   current state and a conflicting proof for the caller fails."
  [directory intent]
  (require-intent! intent)
  (with-store! directory
    (fn [descriptor root]
      (when-let [saved (load-intent! root (:caller-id intent))]
        (require-saved! saved intent))
      (commit! descriptor root :prepared intent nil)
      (load-intent! root (:caller-id intent)))))

(defn begin-attempt!
  "Consume the one submission permit before transmitting. Once the immutable
   attempt exists, even a restart with an unknown outcome returns no permit."
  [directory intent]
  (require-intent! intent)
  (with-store! directory
    (fn [descriptor root]
      (let [caller-id (:caller-id intent)
            saved (load-intent! root caller-id)]
        (require-saved! saved intent)
        (if (= :prepared (:state saved))
          (let [published? (commit! descriptor root :attempted intent nil)
                current (load-intent! root caller-id)]
            (assoc current :permit? (and published? (= :attempted (:state current)))))
          (assoc saved :permit? false))))))

(defn record-accepted!
  "Persist a verified native queue ID for an already attempted intent.
   The caller verifies the exact native peer, request and queue result."
  [directory intent queue-id]
  (require-intent! intent)
  (when-not (id? queue-id) (refuse! :invalid-queue-id))
  (with-store! directory
    (fn [descriptor root]
      (let [caller-id (:caller-id intent)
            saved (load-intent! root caller-id)]
        (require-saved! saved intent)
        (when (= :prepared (:state saved)) (refuse! :not-attempted))
        (commit! descriptor root :accepted intent queue-id)
        (load-intent! root caller-id)))))
