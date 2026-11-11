(ns axxium.law.principal
  "An authenticated principal: who is acting, and how that was proven.
   The boundary verifies a direct AT Protocol login, an Axxium service
   token, a current owner-delegated OAuth grant, or an actor's own bootstrap key
   before constructing a
   principal. This contract only admits their output."
  (:require [axxium.law.did :as did]
            [malli.core :as m]))

(def auth-methods #{:atproto :owner-delegation :service-token :actor-bootstrap})

(def PrincipalContract
  [:and
   [:map {:closed true}
    [:auth/did did/Did]
    [:auth/method (into [:enum] (sort auth-methods))]]
   [:fn (fn [{:auth/keys [did method]}]
          (or (not= :actor-bootstrap method) (did/web? did)))]])

(defn authenticated? [principal]
  (m/validate PrincipalContract principal))

(defn from-actor-bootstrap
  "Principal whose own actor DID was proven with its current bootstrap key."
  [actor-did]
  (let [p {:auth/did actor-did :auth/method :actor-bootstrap}]
    (when (authenticated? p) p)))

(defn from-owner-delegation
  "Principal for the mailbox DID of a verified owner-delegated grant."
  [mailbox-did]
  (let [p {:auth/did mailbox-did :auth/method :owner-delegation}]
    (when (authenticated? p) p)))

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
