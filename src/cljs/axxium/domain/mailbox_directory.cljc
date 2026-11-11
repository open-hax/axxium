(ns axxium.domain.mailbox-directory
  "Owner-scoped routing over the current identity tree and registered mailboxes."
  (:require [axxium.domain.github-identity :as identity]
            [axxium.domain.mailbox :as mailbox]
            [clojure.string :as str]))

(defn enabled?
  "Treat an explicit directory with missing or unavailable bindings as scoped."
  [ctx]
  (contains? ctx :directory/bindings))

(defn- active-root [state did]
  (when (identity/effective? state did)
    (let [root (or (peek (identity/ancestors-of state did)) did)]
      (when (identity/active-human-root? state root) root))))

(defn- controls-mailbox? [event resource]
  (and (string? resource)
       (str/starts-with? resource mailbox/prefix)
       (= (:event/actor event) (subs resource (count mailbox/prefix)))))

(defn- revoked-mailboxes [lg sender now]
  (reduce (fn [revoked {:event/keys [type body] :as event}]
            (case type
              :axxium.grant/revoked
              (let [{:revoke/keys [actor resource actions]} body]
                (if (and (= actor sender) (controls-mailbox? event resource)
                         (or (nil? actions) (some #{:send} actions)))
                  (conj revoked resource)
                  revoked))
              :axxium.grant/granted
              (let [{:grant/keys [actor resource actions expires]} body]
                (if (and (= actor sender) (controls-mailbox? event resource)
                         (some #{:send} actions) (or (nil? expires) (< (inst-ms now) (inst-ms expires))))
                  (disj revoked resource)
                  revoked))
              revoked))
          #{} lg))

(defn- sender-root [state bindings principal]
  (when (= :atproto (:auth/method principal))
    (active-root state (get bindings (:auth/did principal)))))

(defn- reachable-actor [state resources root revoked mailbox-did actor-did]
  (let [box (mailbox/stream mailbox-did)
        {:resource/keys [owner kind]} (get resources box)]
    (when (and (= mailbox-did owner) (= :resource/mailbox kind)
               (not (contains? revoked box))
               (= root (active-root state actor-did)))
      {:actor/did actor-did
       :actor/name (get-in (identity/actor-node state actor-did) [:resource/metadata :actor/name])
       :actor/kind (identity/kind-of state actor-did)
       :mailbox-did mailbox-did})))

(defn peer
  "Return the routable peer for this verified AT principal, or nil. An owner's
   explicit send revocation continues to apply until a live explicit regrant."
  [lg mailbox-state {:keys [principal now] :directory/keys [state bindings]} mailbox-did]
  (when-let [root (sender-root state bindings principal)]
    (reachable-actor state (:resources mailbox-state) root (revoked-mailboxes lg (:auth/did principal) now)
                     mailbox-did (get bindings mailbox-did))))

(defn peers
  "Return the same reachable peers exposed by send and address resolution.
   Nil means the authenticated sender has no current owner-tree binding."
  [lg mailbox-state {:keys [principal now] :directory/keys [state bindings]}]
  (when-let [root (sender-root state bindings principal)]
    (let [revoked (revoked-mailboxes lg (:auth/did principal) now)]
      (->> bindings
           (keep (fn [[mailbox-did actor-did]]
                   (reachable-actor state (:resources mailbox-state) root revoked mailbox-did actor-did)))
           (sort-by :actor/did)
           vec))))
