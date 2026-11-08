(ns axxium.law.identity-admin
  "The admin interface's wire contract: JSON requests from the portal's
   identity manager become identity MCP calls, and results become plain
   JSON views. Pure, so every rule here is testable without a server.

   Secrets flow one way: provision/rotate accept a secret, no view ever
   carries one back. A bootstrap key is the one value shown, once, at issue."
  (:require [clojure.string :as str]))

(def ops
  "Admin op -> identity MCP tool."
  {"list" "actor_list"
   "create" "actor_provision"
   "suspend" "actor_suspend"
   "resume" "actor_resume"
   "revoke" "actor_revoke"
   "credential-provision" "credential_provision"
   "credential-rotate" "credential_rotate"
   "credential-revoke" "credential_revoke"
   "bootstrap-issue" "bootstrap_issue"
   "export" "subtree_export"
   "import" "subtree_import"})

(def creatable-kinds #{"group" "host" "agent" "bot"})
(def provisionable-types #{"github-app" "github-pat" "ssh-key" "api-key"})

(defn- strs [xs] (set (map str (filter some? xs))))
(defn- kws [xs] (set (map keyword (filter string? xs))))

(defn json->policy
  "{repos [..] permissions {name level} actions [..] forbid [..]} -> Policy."
  [{:keys [repos permissions actions forbid]}]
  {:policy/repos (strs repos)
   :policy/permissions (into {} (map (fn [[k v]] [(name k) (str v)])) permissions)
   :policy/actions (kws actions)
   :policy/forbid (kws forbid)})

(defn policy->json [p]
  (when p
    {:repos (vec (sort (:policy/repos p)))
     :permissions (into (sorted-map) (:policy/permissions p))
     :actions (vec (sort (map name (:policy/actions p))))
     :forbid (vec (sort (map name (:policy/forbid p))))}))

(defn child-did
  "A child's DID: <parent>:<slug>. Slugs are lower-case [a-z0-9-]."
  [parent slug]
  (when (and (string? parent) (string? slug) (re-matches #"[a-z0-9][a-z0-9-]{0,62}" slug))
    (str parent ":" slug)))

(defn request->call
  "Translate a portal request (keywordized JSON) made by `caller` into
   [tool args], or {:error ...}. `read-edn` parses import text;
   `host` resolves seed paths."
  [caller {:keys [op did label kind slug name policy type secret env provider seed appId installationId] :as request}
   read-edn host]
  (let [tool (get ops op)]
    (case (when tool op)
      nil {:error "unknown op"}
      "list" [tool {}]
      "export" [tool {}]
      "create"
      (let [parent (get request :parent caller)
            d (child-did parent slug)]
        (cond (not (string? parent)) {:error "parent must be an actor DID"}
              (nil? d) {:error "slug must be lower-case letters, digits and dashes"}
              (not (creatable-kinds kind)) {:error "kind must be group, host, agent or bot"}
              :else [tool (cond-> {:actor/did d :actor/name (or (not-empty name) slug)
                                   :actor/kind (keyword kind) :actor/policy (json->policy policy)}
                            (contains? request :parent) (assoc :actor/parent parent))]))
      ("suspend" "resume" "revoke" "bootstrap-issue")
      (if (string? did) [tool {:actor/did did}] {:error "did required"})
      "credential-provision"
      (cond (not (string? did)) {:error "did required"}
            (not (provisionable-types type)) {:error "unsupported credential type"}
            (str/blank? label) {:error "label required"}
            (str/blank? secret) {:error "secret required"}
            :else [tool (cond-> {:actor/did did :credential/label label
                                 :credential/type (keyword type)}
                          (= "github-app" type) (assoc :credential/pem secret)
                          (not= "github-app" type) (assoc :credential/secret secret)
                          appId (assoc :github/app-id appId)
                          installationId (assoc :github/installation-id installationId)
                          (seq env) (assoc :credential/env env)
                          (seq provider) (assoc :credential/provider provider))])
      "credential-rotate"
      (if (and (string? did) (seq label) (seq secret))
        [tool {:actor/did did :credential/label label :credential/secret secret}]
        {:error "did, label and secret required"})
      "credential-revoke"
      (if (and (string? did) (seq label)) [tool {:actor/did did :credential/label label}]
          {:error "did and label required"})
      "import"
      (let [parsed (try (read-edn (str seed)) (catch :default _ nil))]
        (if (map? parsed) [tool {:seed parsed :host host}] {:error "seed must be one EDN map"})))))

(defn- actor-view [a]
  {:did (:actor/did a) :name (:actor/name a) :parent (:actor/parent a)
   :kind (some-> (:actor/kind a) name) :status (some-> (:actor/status a) name)
   :effective (boolean (:actor/effective? a))
   :credentials (mapv (fn [c] {:label (:credential/label c)
                               :status (some-> (:credential/status c) name)
                               :generation (:credential/generation c)
                               :appSlug (:github/app-slug c)})
                      (:actor/credentials a))})

(defn result->view
  "A JSON-safe view of an MCP result. Never includes secret material:
   only the explicit fields below are copied out."
  [op result pr-edn policies]
  (case op
    "list" {:actors (mapv #(assoc (actor-view %) :policy (policy->json (get policies (:actor/did %))))
                          (:actors result))}
    "export" {:edn (pr-edn (:seed result))}
    "import" {:created (:created result) :unchanged (:unchanged result) :refused (:refused result)}
    "bootstrap-issue" {:bootstrapKey (:bootstrap/key result) :shownOnce true}
    {:ok true}))

