(ns axxium.law.did
  "DID contracts. did:plc names AT Protocol PDS accounts; did:web names the
   Axxium service itself. Axxium never resolves a DID here."
  (:require [clojure.string :as str]
            [malli.core :as m]))

(def plc-pattern
  "did:plc identifiers are 24 lowercase base32 (a-z, 2-7) characters."
  #"did:plc:[a-z2-7]{24}")

(def web-pattern
  "did:web host with optional colon-separated path segments, per the did:web spec."
  #"did:web:[a-z0-9.-]+(?:%3[aA][0-9]+)?(?::[A-Za-z0-9._-]+)*")

(defn plc? [value]
  (boolean (and (string? value) (re-matches plc-pattern value))))

(defn web? [value]
  (boolean (and (string? value)
                (re-matches web-pattern value)
                (not (str/includes? value "..")))))

(defn did?
  "A DID Axxium admits: did:plc for accounts or did:web for services."
  [value]
  (or (plc? value) (web? value)))

(def Did
  [:fn {:error/message "expected did:plc or did:web"} did?])

(defn valid-did? [value]
  (m/validate Did value))
