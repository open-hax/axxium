(ns axxium.shape.ledger-edn
  "Export a ledger as clio EDN lines (one event per line) and read it back.
   The clio canonical form is the identity used for hashing and for the
   round-trip law."
  (:require [clio.shape.canonical :as canonical]
            [clio.shape.edn :as edn]
            [clojure.string :as str]))

(defn export-lines
  "One `pr-str` EDN event per line, newline terminated."
  [ledger]
  (apply str (map #(str (pr-str %) "\n") ledger)))

(defn import-lines
  "Read EDN lines back into a ledger vector. Blank lines are skipped; every
   other line must be exactly one EDN form."
  [text]
  (->> (str/split-lines text)
       (remove str/blank?)
       (mapv edn/read-one)))

(defn canonical-ledger
  "clio canonical EDN for every event, in order."
  [ledger]
  (mapv canonical/canonical-edn ledger))
