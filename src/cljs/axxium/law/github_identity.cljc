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
  (:require [clojure.set :as set]
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
  #{:human :group :host :agent :bot})

(def bootstrap-label "bootstrap")
(def statuses #{:active :suspended :revoked})

(defn actor-resource-id [did] (str "bot-actor:" did))
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

(defn action-allowed?
  "May an actor with `policy` take `action` on a PR? `own?` = the PR was
   authored by this same GitHub identity (no self-approval or self-merge)."
  [policy action {:keys [own?]}]
  (and (contains? (:policy/actions policy) action)
       (not (and own? (= action :approve) (contains? (:policy/forbid policy) :approve-own)))
       (not (and own? (= action :merge) (contains? (:policy/forbid policy) :merge-own)))))

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

