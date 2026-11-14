(ns axxium.extern.ledger-file
  "Single-writer EDN ledger persistence with fsync before success.
   The private mailbox format commits one operation per complete line."
  (:require ["node:fs" :as fs]
            [axxium.law.event :as event]
            [axxium.shape.ledger-edn :as edn-lines]
            [clojure.string :as str]))

(defn read-text "A UTF-8 resource file (e.g. an identity seed)." [path]
  (fs/readFileSync path "utf8"))

(defn- same-file? [stat other]
  (and (= (.-dev stat) (.-dev other)) (= (.-ino stat) (.-ino other))))

(defn- private-stat? [stat]
  (and stat (.isFile stat)
       (= 8r600 (bit-and 8r777 (.-mode stat)))
       (= (.getuid js/process) (.-uid stat))))

(defn- private-file-error []
  (ex-info "Mailbox ledger must remain a regular 0600 file owned by the service user"
           {:reason :mailbox-ledger-permissions}))

(defn- decode-ledger [text]
  (let [end (inc (.lastIndexOf text "\n"))
        complete (subs text 0 end)]
    {:events (edn-lines/import-lines (str/join "\n" (remove str/blank? (str/split-lines complete))))
     :tail-bytes (when (< end (count text)) (.-length (js/Buffer.from complete "utf8")))}))

(def ^:private mailbox-batch-tag :axxium.mailbox/batch-v1)
(def ^:private mailbox-capacity-bytes (* 2 1024 1024))
(def ^:private webhook-settlement-reserve-bytes (* 32 1024))
(def ^:private webhook-settlement-types
  #{:axxium.outbox/delivered :axxium.outbox/webhook-retry :axxium.outbox/webhook-terminal})

(defn- require-mailbox-capacity! [size limit]
  (when (> size limit)
    (throw (ex-info "Mailbox ledger capacity exceeded" {:reason :mailbox-ledger-capacity}))))

(defn- mailbox-frame-events [frame]
  (let [events (cond
                 (map? frame) [frame]
                 (and (vector? frame) (= mailbox-batch-tag (first frame))) (subvec frame 1))]
    (when-not (and (seq events) (every? event/valid-event? events))
      (throw (ex-info "Invalid complete mailbox ledger frame" {:reason :mailbox-ledger-frame})))
    events))

(defn- decode-mailbox-ledger [buffer]
  (let [end (inc (.lastIndexOf buffer 0x0a))
        decoder (js/TextDecoder. "utf-8" #js {:fatal true})
        complete (.decode decoder (.subarray buffer 0 end))]
    {:events (into [] (mapcat mailbox-frame-events) (edn-lines/import-lines complete))
     :tail-bytes (when (< end (.-length buffer)) end)}))

(defn load-ledger [path]
  (if (fs/existsSync path)
    (let [{:keys [events tail-bytes]} (decode-ledger (fs/readFileSync path "utf8"))]
      ;; Validate complete lines before repairing only the uncommitted tail.
      (when (some? tail-bytes) (fs/truncateSync path tail-bytes))
      events)
    []))

(defn write-all!
  "Write every byte or throw if the writer makes no valid progress."
  [buffer write!]
  (let [size (.-length buffer)]
    (loop [offset 0]
      (when (< offset size)
        (let [remaining (- size offset)
              written (write! buffer offset remaining)]
          (when-not (and (number? written) (js/Number.isInteger written)
                         (pos? written) (<= written remaining))
            (throw (js/Error. "ledger write made invalid progress")))
          (recur (+ offset written)))))))

(defn- append-text! [fd text]
  (write-all! (js/Buffer.from text "utf8")
              (fn [buffer offset length]
                (fs/writeSync fd buffer offset length nil)))
  (fs/fsyncSync fd))

(defn append-batch!
  "Persist events of `after` that `before` lacks. Returns `after`."
  [path before after]
  (let [batch (subvec (vec after) (count before))]
    (when (seq batch)
      (let [fd (fs/openSync path "a")]
        (try
          (append-text! fd (edn-lines/export-lines batch))
          (finally (fs/closeSync fd)))))
    after))

(defn- with-private-file [path expected flags file-error f]
  (let [fd (fs/openSync path (bit-or flags (.-O_NOFOLLOW fs/constants) (.-O_NONBLOCK fs/constants)))]
    (try
      (let [stat (fs/fstatSync fd)]
        (when-not (and (private-stat? stat) (same-file? stat expected))
          (throw (file-error)))
        (f fd stat))
      (finally (fs/closeSync fd)))))

(defn- with-drive-ledger [path expected-events mode f]
  (let [expected-stat (fs/lstatSync path)
        invalid-file #(ex-info "Drive ledger must be a service-owned regular 0600 file" {})]
    (when-not (private-stat? expected-stat)
      (throw (invalid-file)))
    (with-private-file path expected-stat mode invalid-file
      (fn [fd _]
        (let [{:keys [events tail-bytes]} (decode-ledger (fs/readFileSync fd "utf8"))]
          (when-not (and (nil? tail-bytes) (= expected-events events))
            (throw (ex-info "Drive ledger changed before upload" {})))
          (f fd))))))

(defn sync-drive-ledger!
  "Durably confirm the exact reservations visible in a pre-provisioned private Drive ledger."
  [path expected-events]
  (with-drive-ledger path expected-events (.-O_RDWR fs/constants) fs/fsyncSync))

(defn append-drive-batch!
  "Append to the verified existing Drive ledger without O_CREAT."
  [path before after]
  (with-drive-ledger path before (bit-or (.-O_RDWR fs/constants) (.-O_APPEND fs/constants))
    (fn [fd]
      (let [batch (subvec (vec after) (count before))]
        (when (seq batch) (append-text! fd (edn-lines/export-lines batch)))
        after))))

(defn private-ledger-storage
  "Pin an existing private mailbox file. Every load and append checks the
   opened descriptor and refuses missing files, symlinks, and replacements.
   Read legacy event lines and write each new operation as one EDN frame.
   The optional single webhook worker reserves space for its in-flight result."
  ([path other-paths] (private-ledger-storage path other-paths false))
  ([path other-paths reserve-webhook-settlement?]
  (let [expected (try (fs/lstatSync path) (catch :default _ nil))]
    (when-not (private-stat? expected)
      (throw (private-file-error)))
    (doseq [other (remove str/blank? other-paths)
            :when (fs/existsSync other)]
      (when (same-file? expected (fs/statSync other))
        (throw (ex-info "Mailbox ledger must be separate from other ledgers"
                        {:reason :mailbox-ledger-shared-file}))))
    {:load! (fn [path]
              (with-private-file
                path expected (.-O_RDWR fs/constants) private-file-error
                (fn [fd stat]
                  (require-mailbox-capacity! (.-size stat) mailbox-capacity-bytes)
                  (let [{:keys [events tail-bytes]} (decode-mailbox-ledger (fs/readFileSync fd))]
                    (when (some? tail-bytes) (fs/ftruncateSync fd tail-bytes))
                    events))))
     :append! (fn [path before after]
                (let [batch (subvec (vec after) (count before))]
                  (with-private-file
                    path expected (bit-or (.-O_WRONLY fs/constants) (.-O_APPEND fs/constants)) private-file-error
                    (fn [fd stat]
                      (when (seq batch)
                        (let [frame (str (pr-str (into [mailbox-batch-tag] batch)) "\n")
                              frame-bytes (js/Buffer.byteLength frame "utf8")
                              settlement? (and reserve-webhook-settlement? (= 1 (count batch))
                                               (contains? webhook-settlement-types (:event/type (first batch))))
                              limit (if (and reserve-webhook-settlement? (not settlement?))
                                      (- mailbox-capacity-bytes webhook-settlement-reserve-bytes)
                                      mailbox-capacity-bytes)]
                          (when settlement?
                            (require-mailbox-capacity! frame-bytes webhook-settlement-reserve-bytes))
                          (require-mailbox-capacity!
                            (+ (.-size stat) frame-bytes) limit)
                          (append-text! fd frame)))
                      after))))})))
