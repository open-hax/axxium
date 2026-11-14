(ns axxium.law.google-state
  "Admission contract for the sealed Google OAuth state (state, nonce, PKCE verifier)."
  (:require [axxium.law.actor :as actor]
            [axxium.law.did :as did]
            [malli.core :as m]))

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

(def DriveActorState
  "A separate OAuth audience binds the actor target and exact operator session."
  [:map {:closed true}
   [:state [:string {:min 1 :max 512}]]
   [:verifier [:string {:min 1 :max 512}]]
   [:actor-did [:and [:string {:max 512}] [:fn did/web?]]]
   [:root-did [:and [:string {:max 512}] [:fn did/web?]]]
   [:session-id [:fn actor/valid-credential-id?]]])

(defn valid-drive-actor-state?
  "Admit only a bounded, one-purpose Drive enrollment state."
  [claims]
  (m/validate DriveActorState claims {:registry (m/default-schemas)}))
