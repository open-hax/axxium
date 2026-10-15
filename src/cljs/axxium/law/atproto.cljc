(ns axxium.law.atproto
  "Admissible AT Protocol identifiers at the identity-resolution boundary."
  (:require [clojure.string :as str]
            [malli.core :as m]))

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

(defn auth-state-capacity-admission
  "After expired entries are purged, decide whether a new authorization-state
   key may be admitted. Rewriting a live key never consumes capacity.
   Returns :admit or :refuse."
  [live-count max-entries key-already-live?]
  (cond
    key-already-live? :admit
    (< live-count max-entries) :admit
    :else :refuse))

(def OAuthStateStoreKey
  "Contract for keys stored in the AT Protocol OAuth authorization-state store."
  [:string {:min 1 :max 2048}])

(def DpopPrivateJwk
  "Private JWK members the ATProto SDK needs to restore a DPoP key.
   kty alone is not enough — import fails without curve/coords and d."
  [:or
   [:map
    [:kty [:= "EC"]]
    [:crv [:enum "P-256" "P-384" "P-521" "secp256k1"]]
    [:x [:string {:min 1 :max 512}]]
    [:y [:string {:min 1 :max 512}]]
    [:d [:string {:min 1 :max 512}]]]
   [:map
    [:kty [:= "OKP"]]
    [:crv [:enum "Ed25519" "Ed448"]]
    [:x [:string {:min 1 :max 512}]]
    [:d [:string {:min 1 :max 512}]]]
   [:map
    [:kty [:= "RSA"]]
    [:n [:string {:min 1 :max 8192}]]
    [:e [:string {:min 1 :max 32}]]
    [:d [:string {:min 1 :max 8192}]]]])

(def OAuthSavedState
  "Plain representation of NodeSavedState (InternalStateData with dpopJwk).
   Validated at the state-store boundary before the SDK opaque handle is kept."
  [:map
   [:iss [:string {:min 1 :max 2048}]]
   [:verifier [:string {:min 1 :max 2048}]]
   [:auth-method
    [:or
     [:map [:method [:= "none"]]]
     [:map [:method [:= "private_key_jwt"]]
      [:kid [:string {:min 1 :max 256}]]]]]
   [:dpop-jwk DpopPrivateJwk]
   [:app-state {:optional true} [:maybe [:string {:min 0 :max 8192}]]]])

(defn valid-oauth-state-store-key?
  "Admit an OAuth state-store key only when it is a bounded non-empty string."
  [k]
  (m/validate OAuthStateStoreKey k {:registry (m/default-schemas)}))

(defn valid-oauth-saved-state?
  "Admit a plain NodeSavedState claims map only when required fields are present."
  [claims]
  (m/validate OAuthSavedState claims {:registry (m/default-schemas)}))
