(ns axxium.extern.ledger-file
  "Single-writer ledger persistence. Batches append canonical EDN lines and
   fsync before success. Recovery truncates an incomplete final line; it
   does not guarantee crash atomicity across several complete event lines."
  (:require ["node:fs" :as fs]
            [axxium.shape.ledger-edn :as edn-lines]
            [clojure.string :as str]))

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

(defn append-batch!
  "Persist events of `after` that `before` lacks. Returns `after`."
  [path before after]
  (let [batch (subvec (vec after) (count before))]
    (when (seq batch)
      (let [fd (fs/openSync path "a")]
        (try
          (fs/writeSync fd (edn-lines/export-lines batch))
          (fs/fsyncSync fd)
          (finally (fs/closeSync fd)))))
    after))
