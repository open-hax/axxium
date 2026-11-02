(ns axxium.extern.ledger-file
  "Durable, atomic ledger persistence: a call's whole batch of new events is
   written as canonical EDN lines in one write and fsynced before the call
   returns its receipt. A crash leaves either all of the batch or none
   (a torn trailing line is dropped on load)."
  (:require ["node:fs" :as fs]
            [axxium.shape.ledger-edn :as edn-lines]
            [clojure.string :as str]))

(defn load-ledger [path]
  (if (fs/existsSync path)
    (let [text (fs/readFileSync path "utf8")
          lines (str/split-lines text)
          complete (if (str/ends-with? text "\n") lines (butlast lines))]
      (edn-lines/import-lines (str/join "\n" (remove str/blank? complete))))
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
