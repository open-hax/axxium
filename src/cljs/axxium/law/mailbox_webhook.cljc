(ns axxium.law.mailbox-webhook
  "Contracts for data retained from a mailbox webhook response."
  (:require [clojure.string :as str]))

(defn response-request-id?
  "Admit a single bounded ASCII request ID and exclude credential prefixes."
  [value]
  (and (string? value)
       (not-any? #(str/starts-with? value %) ["whsec_" "sk-" "rk-"])
       (some? (re-matches #"[A-Za-z0-9_-]{1,128}" value))))
