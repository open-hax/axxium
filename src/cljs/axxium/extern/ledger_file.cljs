(ns axxium.extern.ledger-file
  "Single-writer ledger persistence. Batches append canonical EDN lines and
   fsync before success. Recovery truncates an incomplete final line; it
   does not guarantee crash atomicity across several complete event lines."
  (:require ["node:fs" :as fs]
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

(defn- append-lines! [fd batch]
  (when (seq batch)
    (write-all! (js/Buffer.from (edn-lines/export-lines batch) "utf8")
                (fn [buffer offset length]
                  (fs/writeSync fd buffer offset length nil)))
    (fs/fsyncSync fd)))

(defn append-batch!
  "Persist events of `after` that `before` lacks. Returns `after`."
  [path before after]
  (let [batch (subvec (vec after) (count before))]
    (when (seq batch)
      (let [fd (fs/openSync path "a")]
        (try
          (append-lines! fd batch)
          (finally (fs/closeSync fd)))))
    after))

(defn- with-private-file [path expected flags f]
  (let [fd (fs/openSync path (bit-or flags (.-O_NOFOLLOW fs/constants) (.-O_NONBLOCK fs/constants)))]
    (try
      (let [stat (fs/fstatSync fd)]
        (when-not (and (private-stat? stat) (same-file? stat expected))
          (throw (private-file-error)))
        (f fd))
      (finally (fs/closeSync fd)))))

(defn private-ledger-storage
  "Pin an existing private mailbox file. Every load and append checks the
   opened descriptor and refuses missing files, symlinks, and replacements."
  [path other-paths]
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
                path expected (.-O_RDWR fs/constants)
                (fn [fd]
                  (let [{:keys [events tail-bytes]} (decode-ledger (fs/readFileSync fd "utf8"))]
                    (when (some? tail-bytes) (fs/ftruncateSync fd tail-bytes))
                    events))))
     :append! (fn [path before after]
                (with-private-file
                  path expected (bit-or (.-O_WRONLY fs/constants) (.-O_APPEND fs/constants))
                  (fn [fd]
                    (append-lines! fd (subvec (vec after) (count before)))
                    after)))}))
