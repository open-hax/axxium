(ns axxium.extern.codex-intent-store
  "Linux Node custody for a saved Codex dispatch intent."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [axxium.extern.token-crypto :as token-crypto]
            [clojure.string :as str]))

(def ^:private record-limit 16384)
(def ^:private binding-fields [:actor-did :device-id :thread-id :generation])
(def ^:private successor-prefix "axxium-rollover-")

(defn- refuse! [reason]
  (throw (ex-info "Codex dispatch intent store refused the operation" {:reason reason})))

(defn- id? [value]
  (and (string? value) (<= (count value) 512) (not (str/blank? value))))

(defn- digest? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn- intent? [{:keys [binding caller-id notice-sha256] :as intent}]
  (and (map? intent)
       (= #{:binding :caller-id :notice-sha256} (set (keys intent)))
       (map? binding)
       (= (set binding-fields) (set (keys binding)))
       (every? id? (vals binding))
       (id? caller-id)
       (digest? notice-sha256)))

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

(defn- terminal-detail? [detail]
  (and (vector? detail) (= 3 (count detail))
       (let [[turn-id item-id successor-digest] detail]
         (and (id? turn-id) (id? item-id)
              (or (nil? successor-digest) (digest? successor-digest))))))

(defn- reserved-caller? [caller-id]
  (str/starts-with? caller-id successor-prefix))

(defn- record-version [phase detail]
  (if (or (= :terminal phase) (and (= :prepared phase) (some? detail))) 2 1))

(defn- encode [phase {:keys [binding caller-id notice-sha256]} detail]
  (let [frame [(record-version phase detail) (name phase) caller-id
               (mapv binding binding-fields) notice-sha256 detail]
        bytes (js/Buffer.from (str (js/JSON.stringify (clj->js frame)) "\n") "utf8")]
    (when (> (.-length bytes) record-limit)
      (refuse! :record-too-large))
    bytes))

(defn- decode! [phase caller-id bytes]
  (let [data (try
               (js->clj (js/JSON.parse (.toString bytes "utf8")))
               (catch :default _ (refuse! :invalid-record)))
        frame (when (vector? data) data)
        [version kind saved-caller binding-values notice-sha256 detail] frame]
    (when-not (and (= 6 (count frame))
                   (= (record-version phase detail) version) (= (name phase) kind) (= caller-id saved-caller)
                   (vector? binding-values) (= (count binding-fields) (count binding-values))
                   (case phase
                     :accepted (id? detail)
                     :terminal (terminal-detail? detail)
                     :prepared (or (nil? detail)
                                   (and (id? detail) (not= caller-id detail)
                                        (reserved-caller? caller-id)))
                     (nil? detail)))
      (refuse! :invalid-record))
    (let [intent {:binding (zipmap binding-fields binding-values)
                  :caller-id saved-caller :notice-sha256 notice-sha256}]
      (when-not (and (intent? intent) (.equals bytes (encode phase intent detail)))
        (refuse! :invalid-record))
      {:intent intent :detail detail})))

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

(defn- commit! [descriptor root phase {:keys [caller-id] :as intent} detail]
  (let [temporary (path/join root (str ".intent-" (crypto/randomUUID) ".tmp"))
        filename (record-path root caller-id phase)
        bytes (encode phase intent detail)
        staging (fs/openSync temporary "wx" 8r600)]
    (try
      (try
        (fs/writeFileSync staging bytes)
        (fs/fsyncSync staging)
        (finally (fs/closeSync staging)))
      (let [published? (link-record! temporary filename)]
        (when (and (not published?)
                   (not= {:intent intent :detail detail} (read-record! root caller-id phase)))
          (refuse! :record-conflict))
        (fs/fsyncSync descriptor)
        published?)
      (finally (fs/unlinkSync temporary)))))

(defn- reserved-successor [intent successor-digest]
  (when successor-digest
    {:binding (:binding intent)
     :caller-id (str successor-prefix
                     (token-crypto/stable-digest
                      "codex-dispatch-successor/v1"
                      [(mapv (:binding intent) binding-fields)
                       (:caller-id intent) (:notice-sha256 intent)]))
     :notice-sha256 successor-digest}))

(defn- retire-state [intent accepted {:keys [detail]}]
  (let [[turn-id item-id successor-digest] detail]
    {:state :retired
     :intent (cond-> intent accepted (assoc :queue-id (:detail accepted)))
     :terminal {:turn-id turn-id :item-id item-id}
     :successor-intent (reserved-successor intent successor-digest)}))

(defn- read-intent-records! [root caller-id]
  ;; Observe dependent records first so a concurrent publication can't
  ;; masquerade as an orphan. Earlier records are immutable once linked.
  (let [terminal (read-record! root caller-id :terminal)
        accepted (read-record! root caller-id :accepted)
        attempted (read-record! root caller-id :attempted)
        prepared (read-record! root caller-id :prepared)
        intent (:intent prepared)]
    (when (or (and attempted (not= intent (:intent attempted)))
              (and accepted (or (not attempted) (not= intent (:intent accepted))))
              (and terminal (or (not attempted) (not= intent (:intent terminal)))))
      (refuse! :record-conflict))
    {:terminal terminal :accepted accepted :attempted attempted :prepared prepared}))

(defn- require-successor-slot! [root predecessor successor]
  (when successor
    (when-let [existing (:prepared (read-intent-records! root (:caller-id successor)))]
      (when-not (= {:intent successor :detail predecessor} existing)
        (refuse! :record-conflict)))))

(defn- load-intent! [root caller-id]
  (let [{:keys [terminal accepted attempted prepared]} (read-intent-records! root caller-id)
        intent (:intent prepared)]
    (cond
      terminal (retire-state intent accepted terminal)
      accepted {:state :accepted :intent (assoc intent :queue-id (:detail accepted))}
      attempted {:state :attempted :intent intent}
      prepared {:state :prepared :intent intent})))

(defn- require-saved! [saved intent]
  (when-not (= intent (dissoc (:intent saved) :queue-id))
    (refuse! :intent-mismatch)))

(defn recover!
  "Read one caller's saved intent and its state from the private store.

   A successor reservation is observational here. Use prepare-successor!
   to sync the parent checkpoint before materializing it."
  [directory caller-id]
  (when-not (id? caller-id) (refuse! :invalid-caller-id))
  (with-store! directory (fn [_ root] (load-intent! root caller-id))))

(defn confirm-terminal!
  "Return the binding-matched terminal checkpoint after syncing its directory."
  [directory caller-id binding]
  (when-not (id? caller-id) (refuse! :invalid-caller-id))
  (with-store! directory
    (fn [descriptor root]
      (let [{:keys [state intent] :as saved} (load-intent! root caller-id)]
        (when-not (= :retired state) (refuse! :not-retired))
        (when-not (= binding (:binding intent)) (refuse! :binding-mismatch))
        (fs/fsyncSync descriptor)
        saved))))

(defn- prepare-at! [descriptor root intent predecessor]
  (commit! descriptor root :prepared intent predecessor)
  (load-intent! root (:caller-id intent)))

(defn prepare!
  "Persist the exact intent before submission. An identical retry reads the
   current state and a conflicting proof for the caller fails."
  [directory intent]
  (require-intent! intent)
  (with-store! directory
    (fn [descriptor root]
      (let [caller-id (:caller-id intent)
            saved (load-intent! root caller-id)]
        (if saved
          (do
            (require-saved! saved intent)
            (fs/fsyncSync descriptor)
            (load-intent! root caller-id))
          (do
            (when (reserved-caller? caller-id) (refuse! :reserved-caller-id))
            (prepare-at! descriptor root intent nil)))))))

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

(defn checkpoint-terminal!
  "Retire one attempted intent with a verified completed turn and item.

   verify receives the saved intent from this store. Its trusted caller
   authenticates and reconciles a native capture for that intent. A successor
   digest is an explicit reservation by the caller for a newly observed hint.
   A hold or acceptance result leaves the checkpoint untouched."
  [directory caller-id successor-digest verify]
  (when-not (and (id? caller-id) (fn? verify)
                 (or (nil? successor-digest) (digest? successor-digest)))
    (refuse! :invalid-terminal-request))
  (with-store! directory
    (fn [descriptor root]
      (let [{:keys [state intent]} (load-intent! root caller-id)]
        (case state
          nil {:disposition :hold :reason :missing-intent}
          :prepared {:disposition :hold :reason :not-attempted}
          (let [{:keys [disposition turn-id item-id] :as observed} (verify intent)]
            (case disposition
              :hold observed
              :accepted observed
              :retire
              (let [detail [turn-id item-id successor-digest]]
                (when-not (terminal-detail? detail)
                  (refuse! :invalid-terminal-witness))
                (require-successor-slot! root caller-id (reserved-successor intent successor-digest))
                (commit! descriptor root :terminal (dissoc intent :queue-id) detail)
                (load-intent! root caller-id))
              (refuse! :invalid-terminal-witness))))))))

(defn prepare-successor!
  "Materialize the same reserved successor from a durable parent checkpoint.

   The caller supplies the currently authorized binding. A nil result means
   the exact parent retired without reserving another intent."
  [directory parent-caller-id binding]
  (when-not (id? parent-caller-id)
    (refuse! :invalid-caller-id))
  (with-store! directory
    (fn [descriptor root]
      (let [{:keys [state intent successor-intent]} (load-intent! root parent-caller-id)]
        (when-not (= :retired state) (refuse! :not-retired))
        (when-not (= binding (:binding intent)) (refuse! :binding-mismatch))
        (when successor-intent
          (require-successor-slot! root parent-caller-id successor-intent)
          (fs/fsyncSync descriptor)
          (prepare-at! descriptor root successor-intent parent-caller-id))))))
