(ns axxium.domain.mailbox-directory
  "Owner-scoped routing over the current identity tree and registered mailboxes.

   L6 admit(p) ⇔ ∃n. authn(p)=n ∧ n ≤ root ∧ eff(n): an `:actor-node`
   principal is its node; an AT Protocol principal is the node the ledger
   binds its DID to (B1). A node's address is its bound mailbox, or its own
   DID when none is bound, so an agent such as Grok Bot gets its own mailbox
   without borrowing an account. S4 send ⇔ see within one tree."
  (:require [axxium.domain.github-identity :as identity]
            [axxium.domain.mailbox :as mailbox]
            [axxium.law.principal :as principal]
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

(defn node-address
  "The mailbox address of actor node `n`: its bound mailbox, else its DID."
  [bindings n]
  (or (some (fn [[m a]] (when (= a n) m)) bindings) n))

(defn- address-actor
  "The actor node reachable at mailbox address `m`, if any."
  [state bindings m]
  (or (get bindings m)
      (when (and (identity/actor-node state m) (= m (node-address bindings m))) m)))

(defn authenticated-node
  "L6: the actor node a principal authenticates as, or nil."
  [state bindings p]
  (case (:auth/method p)
    :actor-node (let [n (:auth/did p)]
                  (when (and (identity/actor-node state n)
                             (= (principal/address p) (node-address bindings n)))
                    n))
    :atproto (get bindings (:auth/did p))
    nil))

(defn- sender-root [state bindings principal]
  (some->> (authenticated-node state bindings principal) (active-root state)))

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
  "Return the routable peer for this verified mailbox principal, or nil. An owner's
   explicit send revocation continues to apply until a live explicit regrant."
  [lg mailbox-state {:keys [principal now] :directory/keys [state bindings]} mailbox-did]
  (when-let [root (sender-root state bindings principal)]
    (reachable-actor state (:resources mailbox-state) root (revoked-mailboxes lg (principal/address principal) now)
                     mailbox-did (address-actor state bindings mailbox-did))))

(defn peers
  "Return the same reachable peers exposed by send and address resolution.
   Nil means the authenticated sender has no current owner-tree binding."
  [lg mailbox-state {:keys [principal now] :directory/keys [state bindings]}]
  (when-let [root (sender-root state bindings principal)]
    (let [revoked (revoked-mailboxes lg (principal/address principal) now)]
      (->> (identity/actors-under state root)
           (keep (fn [{actor-did :actor/did}]
                   (reachable-actor state (:resources mailbox-state) root revoked
                                    (node-address bindings actor-did) actor-did)))
           (sort-by :actor/did)
           vec))))
