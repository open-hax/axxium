(ns axxium.law.google-state
  "Admission contract for the sealed Google OAuth state (state, nonce, PKCE verifier)."
  (:require [malli.core :as m]))

(def GoogleState
  "Contract for the claims carried by a sealed Google OAuth state token."
  [:map
   [:state [:string {:min 1 :max 512}]]
   [:nonce [:string {:min 1 :max 512}]]
   [:verifier [:string {:min 1 :max 512}]]])

(defn valid-google-state?
  "Admit Google OAuth state claims only when all three are non-empty strings."
  [claims]
  (m/validate GoogleState claims {:registry (m/default-schemas)}))
