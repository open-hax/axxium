(ns axxium.domain.github-identity
  "The bot identity tree and its credentials, as Axxium resources (pure).

   Every change is a `:axxium.resource/registered` event (re-registration
   replaces the resource), preceded by a recorded policy decision (law 7).
   The rule for each operation is the hierarchy law in
   `axxium.law.github-identity`; an authority grant cannot bypass it,
   because a structurally invalid operation is decided with no effect."
  (:require [axxium.domain.ledger :as ledger]
            [axxium.domain.policy :as policy]
            [axxium.domain.state :as state]
            [axxium.law.github-identity :as law]))

(defn actor-node [state did]
  (let [r (get-in state [:resources (law/actor-resource-id did)])]
    (when (= law/actor-kind (:resource/kind r)) r)))

(defn parent-of [state did] (get-in (actor-node state did) [:resource/metadata :actor/parent]))
(defn status-of [state did] (get-in (actor-node state did) [:resource/metadata :actor/status]))
(defn policy-of [state did] (get-in (actor-node state did) [:resource/metadata :actor/policy]))

(defn ancestors-of
  "Parent first, root last. Bounded by the tree size, so a cycle cannot hang."
  [state did]
  (loop [d (parent-of state did) acc [] seen #{did}]
    (if (or (nil? d) (seen d)) acc (recur (parent-of state d) (conj acc d) (conj seen d)))))

(defn effective?
  "G3: the actor and every ancestor are :active."
  [state did]
  (and (actor-node state did)
       (every? #(= :active (status-of state %)) (cons did (ancestors-of state did)))))

(defn ancestor? [state operator did]
  (boolean (some #{operator} (ancestors-of state did))))

(defn may-manage?
  "G2: operator is a strict ancestor of `did` and is itself effective."
  [state operator did]
  (and (ancestor? state operator did) (effective? state operator)))

(defn kind-of [state did] (get-in (actor-node state did) [:resource/metadata :actor/kind]))

(defn active-human-root?
  "An admitted, effective human node that owns its DID and has no parent."
  [state did]
  (and (= :human (kind-of state did))
       (= did (:resource/owner (actor-node state did)))
       (nil? (parent-of state did))
       (effective? state did)
       (law/valid-policy? (policy-of state did))))

(defn- node [did parent name pol status & [kind]]
  {:resource/id (law/actor-resource-id did)
   :resource/kind law/actor-kind
   :resource/owner did
   :resource/store :axxium
   :resource/metadata {:actor/name name :actor/parent parent
                       :actor/policy pol :actor/status status
                       :actor/kind (or kind :bot)}})

(defn- act
  "Decide (recorded), then register `r` only when `ok?` and the decision allows."
  [lg state operator action r ok? {:keys [at] :as ids}]
  (policy/act lg state
              {:actor operator :resource (:resource/id r) :action action :at at}
              (when ok? {:stream "resources" :type :axxium.resource/registered
                         :actor operator :body r})
              ids
              (constantly ok?)))

(defn register-root
  "A human root (e.g. did:web:<host>:actors:err) registers itself."
  [lg state did name pol ids]
  (act lg state did :register (node did nil name pol :active :human)
       (and (nil? (actor-node state did)) (law/valid-policy? pol)) ids))

(defn provision-actor
  "Parent creates a child actor whose policy is within its own (G1).
   `kind` is one of law/actor-kinds other than :human (default :bot)."
  ([lg state parent did name pol ids] (provision-actor lg state parent did name pol :bot ids))
  ([lg state parent did name pol kind ids]
   (act lg state parent :provision (node did parent name pol :active kind)
        (and (nil? (actor-node state did)) (effective? state parent)
             (contains? (disj law/actor-kinds :human) kind)
             (law/policy-within? pol (policy-of state parent))) ids)))

(defn- set-status [lg state operator did status ids]
  (let [n (actor-node state did)]
    (act lg state operator status
         (assoc-in (or n (node did nil "?" nil status)) [:resource/metadata :actor/status] status)
         (and n (not= :revoked (status-of state did)) (may-manage? state operator did)) ids)))

(defn suspend [lg state operator did ids] (set-status lg state operator did :suspended ids))
(defn resume [lg state operator did ids] (set-status lg state operator did :active ids))

(defn set-policy [lg state operator did pol ids]
  (let [n (actor-node state did)]
    (act lg state operator :set-policy
         (assoc-in (or n (node did nil "?" pol :active)) [:resource/metadata :actor/policy] pol)
         (and n (may-manage? state operator did)
              (law/policy-within? pol (policy-of state (parent-of state did)))) ids)))

;; -------------------------------------------------------------- credentials

(defn credential [state did label]
  (let [r (get-in state [:resources (law/credential-resource-id did label)])]
    (when (and (= law/credential-kind (:resource/kind r)) (= did (:resource/owner r))) r)))

(defn credentials [state did]
  (->> (vals (:resources state))
       (filter #(and (= law/credential-kind (:resource/kind %)) (= did (:resource/owner %))))
       (sort-by :resource/id) vec))

(defn credential-resource
  "`secret` is {:credential/sealed <AES-GCM envelope bound to did>} or
   {:credential/key-file <path>} for an imported key that stays where it is."
  [did label type secret metadata generation]
  {:resource/id (law/credential-resource-id did label)
   :resource/kind law/credential-kind
   :resource/owner did
   :resource/store :axxium
   :resource/location (merge {:credential/type type} secret)
   :resource/metadata (assoc metadata :credential/label label
                             :credential/status :active :credential/generation generation)})

(defn provision-credential
  "An ancestor attaches a new credential to an actor (never plaintext)."
  [lg state operator did label type secret metadata ids]
  (act lg state operator :provision (credential-resource did label type secret metadata 1)
       (and (contains? law/credential-types type)
            (nil? (credential state did label))
            (effective? state did) (may-manage? state operator did)) ids))

(defn rotate-credential
  "Replace the secret (and optionally metadata); generation increments."
  [lg state operator did label secret metadata ids]
  (let [c (credential state did label)
        gen (inc (get-in c [:resource/metadata :credential/generation] 0))]
    (act lg state operator :rotate
         (credential-resource did label (get-in c [:resource/location :credential/type] :github-app)
                              secret (merge (dissoc (:resource/metadata c) :credential/status
                                                    :credential/generation :credential/label)
                                            metadata) gen)
         (and c (= :active (get-in c [:resource/metadata :credential/status]))
              (may-manage? state operator did)) ids)))

(defn revoke-credential
  "Revocation is terminal and drops the secret from the current resource
   (the ledger keeps only the sealed envelope it always had)."
  [lg state operator did label ids]
  (let [c (credential state did label)]
    (act lg state operator :revoke
         (-> (or c (credential-resource did label :github-app {} {} 0))
             (update :resource/location select-keys [:credential/type])
             (assoc-in [:resource/metadata :credential/status] :revoked))
         (and c (may-manage? state operator did)) ids)))

(defn revoke-actor
  "Terminal: the actor and (by G3) all descendants stop being effective."
  [lg state operator did ids]
  (set-status lg state operator did :revoked ids))

(defn usable-credential
  "The credential an actor may mint with, or nil."
  [state did label]
  (let [c (credential state did label)]
    (when (and c (effective? state did)
               (= :active (get-in c [:resource/metadata :credential/status])))
      c)))

(defn decide-mint
  "Record the decision to export a read-only token for `did`.
   Allowed for the actor itself or a managing ancestor, within policy (G4)."
  [lg state operator did label scope {:keys [at] :as ids}]
  (let [ok? (boolean (and (usable-credential state did label)
                          (or (= operator did) (may-manage? state operator did))
                          (law/raw-token-scope-within? scope (policy-of state did))))]
    (assoc (policy/act lg state {:actor operator :resource (law/credential-resource-id did label)
                                 :action :mint :at at}
                       nil ids (constantly ok?))
           :allow? ok?)))

(defn actors-under
  "Actors visible to `did`: itself and its descendants, sorted."
  [state did]
  (->> (vals (:resources state))
       (filter #(= law/actor-kind (:resource/kind %)))
       (map :resource/owner)
       (filter #(or (= % did) (ancestor? state did %)))
       sort
       (mapv (fn [d] {:actor/did d
                      :actor/name (get-in (actor-node state d) [:resource/metadata :actor/name])
                      :actor/parent (parent-of state d)
                      :actor/kind (kind-of state d)
                      :actor/status (status-of state d)
                      :actor/effective? (boolean (effective? state d))
                      :actor/credentials (mapv #(select-keys (:resource/metadata %)
                                                             [:credential/label :credential/status
                                                              :credential/generation :github/app-slug])
                                               (credentials state d))}))))

;; ------------------------------------------------------------ bootstrap

(defn issue-bootstrap
  "An ancestor issues (or rotates) an agent's device credential. Only its
   SHA-256 digest is recorded; the plaintext is shown to the issuer once."
  [lg state operator did digest ids]
  (if (credential state did law/bootstrap-label)
    (rotate-credential lg state operator did law/bootstrap-label {:credential/digest digest} {} ids)
    (provision-credential lg state operator did law/bootstrap-label :bootstrap-key
                          {:credential/digest digest} {} ids)))

(defn bootstrap-digest
  "The digest an agent must present, only while agent and ancestors are effective."
  [state did]
  (get-in (usable-credential state did law/bootstrap-label) [:resource/location :credential/digest]))

(defn own-credentials
  "An effective actor's active credentials other than its bootstrap key."
  [state did]
  (if (effective? state did)
    (->> (credentials state did)
         (filter #(= :active (get-in % [:resource/metadata :credential/status])))
         (remove #(= :bootstrap-key (get-in % [:resource/location :credential/type])))
         vec)
    []))

;; ------------------------------------------------------------------ seed
;;
;; A seed is a secret-free EDN resource file describing a tree:
;;   {:policies {<kw> <Policy>}             ; optional named policies
;;    :root  {:id <path> :name <str> :policy <Policy|kw>}
;;    :nodes [{:id <path> :parent <path> :kind <kind> :name <str>
;;             :policy <Policy|kw>} ...]}  ; parents before children
;; Ids are actor paths ("alice:hosts:laptop") resolved on a host to
;; did:web:<host>:actors:<path>, or full DIDs used as-is.

(defn- path->did [host id]
  (if (re-find #"^did:" id) id (str "did:web:" host ":actors:" id)))

(defn resolve-seed
  "Resolve paths to DIDs and named policies to Policy maps."
  [{:keys [policies root nodes]} host]
  (let [did #(when % (path->did host %))
        pol #(if (keyword? %) (get policies %) %)]
    {:root (when root {:did (did (:id root)) :name (:name root) :policy (pol (:policy root))})
     :nodes (mapv (fn [n] {:did (did (:id n)) :parent (did (:parent n)) :name (:name n)
                           :kind (:kind n) :policy (pol (:policy n))})
                  nodes)}))

(defn apply-seed
  "Idempotently apply resolved `nodes` as `operator`, through the same
   policy-checked provision op (G1 against each node's parent; G2: the
   operator must be that parent or manage it). Existing actors are left as they are
   (:unchanged); anything the operator may not create is :refused.
   With `root` and an empty slot, the root registers itself first.
   Returns {:ledger :created [] :unchanged [] :refused []}."
  [lg operator {:keys [root nodes]} ids]
  (let [lg (if (and root (= operator (:did root)) (nil? (actor-node (state/fold lg) (:did root))))
             (:ledger (register-root lg (state/fold lg) (:did root) (:name root) (:policy root) (ids)))
             lg)]
    (reduce (fn [{lg :ledger :as acc} {:keys [did parent name kind policy]}]
              (let [st (state/fold lg)]
                (cond
                  (actor-node st did) (update acc :unchanged conj did)
                  ;; G2: the operator must be the parent or manage it
                  (not (or (and (= operator parent) (effective? st parent))
                           (may-manage? st operator parent)))
                  (update acc :refused conj did)
                  :else
                  (let [out (provision-actor lg st parent did name policy (or kind :bot) (ids))]
                    (-> (assoc acc :ledger (:ledger out))
                        (update (if (:effect out) :created :refused) conj did))))))
            {:ledger lg :created [] :unchanged [] :refused []}
            nodes)))

(defn seed-ledger
  "A fresh ledger from a resolved seed, applied as its root."
  [seed ids]
  (apply-seed ledger/empty-ledger (get-in seed [:root :did]) seed ids))

(defn export-subtree
  "The caller's descendants as a resolved, secret-free seed (full DIDs,
   parents before children). Credentials are never exported."
  [state did]
  {:nodes (->> (actors-under state did)
               (remove #(= did (:actor/did %)))
               (sort-by #(count (ancestors-of state (:actor/did %))))
               (mapv (fn [{d :actor/did}]
                       (let [m (:resource/metadata (actor-node state d))]
                         {:id d :parent (:actor/parent m) :kind (:actor/kind m)
                          :name (:actor/name m) :policy (:actor/policy m)}))))})

