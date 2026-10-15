(ns axxium.law.atproto
  "Admissible AT Protocol identifiers at the identity-resolution boundary."
  (:require [clojure.string :as str]))

(defn did? [value]
  (and (string? value)
       (<= (count value) 512)
       (or (boolean (re-matches #"did:plc:[a-z2-7]{24}" value))
           (boolean (re-matches #"did:web:[a-zA-Z0-9.:%_-]+" value)))))

(defn handle? [value]
  (and (string? value)
       (<= 3 (count value) 253)
       (not (str/starts-with? value "did:"))
       (boolean (re-matches #"(?i)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+" value))))

(defn identity? [value]
  (or (did? value) (handle? value)))

(defn concurrent-binding-admission
  "Classify a concurrent provider/subject unique-index winner for a link attempt.
   Pure contract: the route queries and persists; this only decides admissibility.
   Returns :missing (winner vanished), :same-actor (proceed), or :other-actor (409)."
  [requesting-actor-id winner]
  (cond
    (nil? winner) :missing
    (= requesting-actor-id (:id winner)) :same-actor
    :else :other-actor))

