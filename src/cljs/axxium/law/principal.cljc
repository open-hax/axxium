(ns axxium.law.principal
  "An authenticated principal: who is acting, and how that was proven.

   L5 one identity path: a principal is either a verified external account
   (a direct AT Protocol login or an Axxium service token) or an actor node
   authenticating as itself (`:actor-node`, `authn(p) = n`). No principal is
   ever minted from another actor's session; an owner's session can only
   witness the manage right (L4) when a node's credential is issued.

   `:auth/mailbox` is the mailbox address the principal acts at. It is
   absent when the address is `:auth/did` itself, and for a node it is the
   external mailbox the ledger binds to that node (law B1). The boundary
   verifies the credential before constructing a principal; this contract
   only admits its output."
  (:require [axxium.law.did :as did]
            [malli.core :as m]))

(def auth-methods #{:actor-node :atproto :service-token})

(def PrincipalContract
  [:and
   [:map {:closed true}
    [:auth/did did/Did]
    [:auth/method (into [:enum] (sort auth-methods))]
    [:auth/mailbox {:optional true} did/Did]]
   [:fn {:error/message "only an actor node is a did:web that may act at a bound mailbox"}
    (fn [{:auth/keys [did method mailbox]}]
      (if (= :actor-node method)
        (did/web? did)
        (nil? mailbox)))]])

(defn authenticated? [principal]
  (m/validate PrincipalContract principal))

(defn address
  "The mailbox address a principal acts at."
  [principal]
  (or (:auth/mailbox principal) (:auth/did principal)))

(defn from-actor-node
  "Principal for a verified actor node acting as itself, optionally at the
   external mailbox the ledger currently binds to it."
  ([node-did] (from-actor-node node-did nil))
  ([node-did mailbox-did]
   (let [p (cond-> {:auth/did node-did :auth/method :actor-node}
             (and mailbox-did (not= mailbox-did node-did)) (assoc :auth/mailbox mailbox-did))]
     (when (authenticated? p) p))))

(defn from-service-claims
  "Principal for verified service-token claims carrying a `did` claim."
  [claims]
  (let [p {:auth/did (get claims "did" (:did claims)) :auth/method :service-token}]
    (when (authenticated? p) p)))

(defn from-atproto-session
  "Principal for a completed AT Protocol OAuth session's DID."
  [session-did]
  (let [p {:auth/did session-did :auth/method :atproto}]
    (when (authenticated? p) p)))
