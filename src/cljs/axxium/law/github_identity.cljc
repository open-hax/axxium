(ns axxium.law.github-identity
  "Contracts for bot identities and their credentials.

   Objects: actor nodes (one per DID) forming a tree under a human root;
   credential resources owned by exactly one actor. Morphisms: provision,
   rotate, suspend, revoke, mint. Laws:

   G1 a child's policy is a subset of its parent's (repos, permissions,
      actions; forbids only grow);
   G2 only a strict, effectively active ancestor may provision, suspend or
      revoke an actor or its credentials; siblings never can;
   G3 an actor is effective only when it and every ancestor are active
      (suspension cascades);
   G4 a minted token's scope is a subset of the holder's policy;
   G5 secrets are sealed to the owning DID; plaintext never enters the ledger.

   A credential is generic over :credential/type; :github-app is the first
   (GitHub blocks automated user sign-up, so each bot is its own App and
   shows as <slug>[bot]). :github-pat and :ssh-key share the same tree."
  (:require [axxium.law.did :as did]
            [axxium.law.event :as event]
            [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]))

(def actor-kind :resource/bot-actor)
(def credential-kind :resource/credential)
(def credential-types
  "Generic over what the secret is. :bootstrap-key is a per-agent device
   credential stored only as a SHA-256 digest; the others are sealed."
  #{:github-app :github-pat :ssh-key :api-key :bootstrap-key})

(def actor-kinds
  "Node kinds in the identity tree: a human root, grouping nodes (e.g. the
   Grok Bots), machines, coding agents on a machine, and service bots."
  #{:human :group :host :agent :bot :session})

;; S1 a :session node is a leased child of an agent, bot or session; its
;;    policy is within its parent's (G1) and its lease never outlives the
;;    parent's, so cred(s) = attenuate(cred(parent(s)));
;; S2 a live node advertises itself at an ancestor-or-self anchor; the
;;    directory D(anchor) is the fold of advertise/withdraw events, and
;;    expired or ineffective nodes drop out;
;; S3 see(v,s) <=> root(v) = root(s) and both live; find(v, anchor, q) is
;;    the advertised nodes at anchor that v sees and q matches.
(def session-parent-kinds #{:agent :bot :session})
(def max-lease-ms 86400000)

(def ^:private claim-identifier
  [:and [:string {:min 1 :max 512}] [:re #"^[!-~]+$"]])

(def ^:private claim-did
  [:and [:string {:max 1024}] [:fn did/web?]])

(def ^:private SessionClaimProof
  [:map {:closed true}
   [:claim/owner-did claim-did]
   [:claim/connector-did claim-did]
   [:claim/issuer [:and claim-identifier [:re #"^https://[^?#]+$"]]]
   [:claim/client-id claim-identifier]
   [:claim/subject claim-identifier]
   [:claim/product [:enum :work :dot :chat :codex]]
   [:claim/context [:or [:tuple [:= :general]] [:tuple [:= :project] claim-identifier]]]
   [:claim/conversation-id claim-identifier]
   [:claim/session-id claim-identifier]
   [:claim/expires-at [:fn inst?]]])

(def ^:private SessionClaimCandidate
  [:map {:closed true}
   [:actor/did claim-did]
   [:actor/name [:and [:string {:min 1 :max 160}] [:fn (complement str/blank?)]]]
   [:actor/lease-until [:fn inst?]]])

(defn valid-session-claim?
  "Check the shape of server-verified delegation and its proposed child."
  [proof candidate]
  (and (m/validate SessionClaimProof proof)
       (m/validate SessionClaimCandidate candidate)))

(defn direct-session-child?
  "A valid session DID uses exactly one suffix segment below its connector."
  [connector child]
  (let [prefix (str connector ":")]
    (and (did/web? connector) (did/web? child)
         (str/starts-with? child prefix)
         (not (str/includes? (subs child (count prefix)) ":")))))

(def advert-kind :resource/actor-advert)
(defn advert-resource-id [did] (str "actor-advert:" did))

(def Tags [:set {:max 16} [:re #"^[a-z0-9][a-z0-9._:-]{0,63}$"]])
(defn valid-tags? [tags] (m/validate Tags tags))

(def bootstrap-label "bootstrap")
(def statuses #{:active :suspended :revoked})

(defn actor-resource-id [did] (str "bot-actor:" did))

;; B1 a mailbox binding maps one external mailbox DID (e.g. an AT Protocol
;;    did:plc account) to one actor node: `bind(m, n)` is a ledger resource
;;    `mailbox-binding:<m>` owned by n, so binding, suspension and revocation
;;    fold from the same log;
;; B2 only a strict, effectively active ancestor of n may bind or unbind (G2);
;; B3 bindings are one-to-one while :bound, and only an effective n is bound.
(def binding-kind :resource/mailbox-binding)
(def binding-statuses #{:bound :unbound})
(defn binding-resource-id [mailbox-did] (str "mailbox-binding:" mailbox-did))
(defn credential-resource-id [did label] (str "credential:" did ":" label))

(def levels {"none" 0 "read" 1 "write" 2 "admin" 3})

(def actions
  "What a credential holder may do with GitHub through Axxium."
  #{:read :comment :review :approve :push :merge})

(def Policy
  [:map {:closed true}
   [:policy/repos [:set [:string {:min 1}]]]          ; "owner/repo" or "*"
   [:policy/permissions [:map-of :string (into [:enum] (keys levels))]]
   [:policy/actions [:set (into [:enum] (sort actions))]]
   [:policy/forbid [:set [:enum :approve-own :merge-own :force-push]]]])

(defn valid-policy? [p] (m/validate Policy p))

(defn- repos-within? [child parent]
  (or (contains? parent "*")
      (and (not (contains? child "*")) (set/subset? child parent))))

(defn- perms-within? [child parent]
  (every? (fn [[k v]] (<= (levels v) (levels (get parent k "none")))) child))

(defn policy-within?
  "G1 (and G4 for scopes): `child` grants nothing `parent` does not."
  [child parent]
  (boolean
   (and (valid-policy? child) (valid-policy? parent)
        (repos-within? (:policy/repos child) (:policy/repos parent))
        (perms-within? (:policy/permissions child) (:policy/permissions parent))
        (set/subset? (:policy/actions child) (:policy/actions parent))
        (set/subset? (:policy/forbid parent) (:policy/forbid child)))))

(def Scope
  "A token request: concrete repo names and permissions."
  [:map {:closed true}
   [:scope/repos [:vector {:min 1} [:re #"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"]]]
   [:scope/permissions [:map-of :string [:enum "read" "write"]]]])

(defn scope-within? [scope policy]
  (and (m/validate Scope scope)
       (repos-within? (set (:scope/repos scope)) (:policy/repos policy))
       (perms-within? (:scope/permissions scope) (:policy/permissions policy))))

(def ^:private exported-read-permissions
  #{"checks" "contents" "issues" "metadata" "pull_requests" "statuses"})

(defn raw-token-scope-within?
  "Only export explicitly scoped repository reads. A GitHub bearer token
   cannot express individual write actions or restrictions on a holder's PR."
  [{:scope/keys [permissions] :as scope} policy]
  (and (scope-within? scope policy)
       (contains? (:policy/actions policy) :read)
       (seq permissions)
       (every? (fn [[permission level]]
                 (and (= "read" level) (contains? exported-read-permissions permission)))
               permissions)))

(defn action-allowed?
  "May an actor with `policy` take `action` on a PR? `own?` = the PR was
   authored by this same GitHub identity (no self-approval or self-merge)."
  [policy action {:keys [own?]}]
  (and (contains? (:policy/actions policy) action)
       (not (and own? (= action :approve) (contains? (:policy/forbid policy) :approve-own)))
       (not (and own? (= action :merge) (contains? (:policy/forbid policy) :merge-own)))))

(defn valid-comment-request?
  "Accept a single exact target, one idempotency key and a bounded comment."
  [{:github/keys [repo number body request-id] :as request}]
  (and (map? request)
       (= (set (keys request)) #{:actor/did :credential/label :github/repo :github/number :github/body :github/request-id})
       (string? (:actor/did request)) (<= 1 (count (:actor/did request)) 1024)
       (string? (:credential/label request)) (<= 1 (count (:credential/label request)) 128)
       (string? repo) (<= (count repo) 217)
       (re-matches #"[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+" repo)
       (not (#{"." ".."} (last (str/split repo #"/"))))
       (integer? number) (<= 1 number 2147483647)
       (event/uuid-string? request-id)
       (string? body) (not (str/blank? body)) (<= (count body) 16384)))

(defn comment-provider-diagnostics
  "Admit only safe GitHub comment response metadata for storage or output."
  [{:github/keys [phase target-kind http-status provider-request-id]}]
  (when (and (= :comment-post phase) (contains? #{:issue :pull-request} target-kind)
             (integer? http-status) (<= 100 http-status 599))
    (cond-> {:github/phase phase :github/target-kind target-kind :github/http-status http-status}
      (and (string? provider-request-id)
           (re-matches #"[A-Fa-f0-9]{1,16}(?::[A-Fa-f0-9]{1,16}){2,7}" provider-request-id))
      (assoc :github/provider-request-id (str/upper-case provider-request-id)))))

;; ------------------------------------------------------------ app manifest

(def manifest-callback-path "/api/github/manifest/callback")

(defn- slugify [s]
  (-> s str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defn app-manifest
  "A GitHub App manifest for one actor. Permissions mirror the actor's
   policy (no admin), webhooks off, private (installable only by owner)."
  [{:keys [public-base-url actor-name policy]}]
  {:name (subs (str (slugify actor-name)) 0 (min 34 (count (slugify actor-name))))
   :url public-base-url
   :redirect_url (str public-base-url manifest-callback-path)
   :public false
   :hook_attributes {:active false :url (str public-base-url "/api/github/hooks")}
   :default_permissions (into {"metadata" "read"}
                              (for [[k v] (:policy/permissions policy) :when (#{"read" "write"} v)]
                                [k v]))
   :default_events []})

(defn attribution-marker
  "Comment marker naming the Axxium actor behind a GitHub action."
  [did]
  (str "<!-- axxium-actor:v1 did=" did " -->"))
