(ns axxium.extern.ledger-file
  "Single-writer ledger persistence. Batches append canonical EDN lines and
   fsync before success. Recovery truncates an incomplete final line; it
   does not guarantee crash atomicity across several complete event lines."
  (:require ["node:fs" :as fs]
            [axxium.shape.ledger-edn :as edn-lines]
            [clojure.string :as str]))

(defn read-text "A UTF-8 resource file (e.g. an identity seed)." [path]
  (fs/readFileSync path "utf8"))

(defn load-ledger [path]
  (if (fs/existsSync path)
    (let [text (fs/readFileSync path "utf8")
          end (inc (.lastIndexOf text "\n"))
          complete (subs text 0 end)
          admitted (edn-lines/import-lines (str/join "\n" (remove str/blank? (str/split-lines complete))))]
      ;; Validate complete lines before repairing only the uncommitted tail.
      (when (< end (count text))
        (fs/truncateSync path (.-length (js/Buffer.from complete "utf8"))))
      admitted)
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

(defn append-batch!
  "Persist events of `after` that `before` lacks. Returns `after`."
  [path before after]
  (let [batch (subvec (vec after) (count before))]
    (when (seq batch)
      (let [fd (fs/openSync path "a")]
        (try
          (write-all! (js/Buffer.from (edn-lines/export-lines batch) "utf8")
                      (fn [buffer offset length]
                        (fs/writeSync fd buffer offset length nil)))
          (fs/fsyncSync fd)
          (finally (fs/closeSync fd)))))
    after))

