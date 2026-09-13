(ns axxium.law.identity
  "Pure admissibility contracts for credentials and portable identity claims."
  (:require [malli.core :as m]))
(def credentials
  [:map [:email [:re #"^[^\s@]+@[^\s@]+\.[^\s@]+$"]]
   [:password [:string {:min 12 :max 72}]]])
(def transfer-claims
  [:map [:sub [:string {:min 1 :max 128}]]
   [:entity_id [:string {:min 1 :max 128}]]
   [:email [:re #"^[^\s@]+@[^\s@]+\.[^\s@]+$"]]
   [:display_name [:string {:min 1 :max 200}]]
   [:jti [:string {:min 1 :max 128}]] [:iss :string] [:aud :string]
   [:iat :int] [:exp :int] [:purpose [:= "identity-transfer"]]])
(defn require-valid! "Reject invalid boundary data without echoing credentials or tokens." [schema value]
  (when-not (m/validate schema value)
    (throw (ex-info "Invalid identity request" {:status 400 :code "invalid_request"}))) value)
(defn require-transfer! "Require verified identity-only claims and bounded lifetime." [claims now]
  (require-valid! transfer-claims claims)
  (when-not (and (<= (:iat claims) now) (< now (:exp claims))
                 (<= (- (:exp claims) (:iat claims)) 300))
    (throw (ex-info "Identity transfer expired or invalid" {:status 401 :code "invalid_transfer"})))
  claims)
