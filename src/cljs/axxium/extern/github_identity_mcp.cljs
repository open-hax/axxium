(ns axxium.extern.github-identity-mcp
  "MCP tools over the bot identity tree.

   actor_list            actors visible to the caller (self + descendants)
   actor_provision       create a child actor (policy within caller's)
   actor_suspend/resume  an ancestor pauses a subtree (cascades, G3)
   actor_revoke          terminal
   credential_provision  attach a credential: sealed PEM, or an imported
                         key file left in place (an existing App)
   credential_rotate     new secret / installation id, generation + 1
   credential_revoke     terminal
   token_for             mint a scoped installation token (in-memory cache)
   subtree_export        the caller's descendants as secret-free seed EDN
   subtree_import        idempotently create missing actors from seed EDN
   bootstrap_issue       ancestor issues/rotates an agent's device key;
                         the plaintext is returned once, only a digest kept
   credentials_self      the caller's own unsealed :api-key / :ssh-key /
                         :github-pat secrets and :github-app metadata

   Host-side agents call with ctx {:actor <own did>} established by
   `authenticate` (did + bootstrap key), so they never hold a parent's
   secrets and can only ever read their own.

   (call deps ctx tool args) -> Promise<{:ledger :result} | {:error ...}>
   deps = {:fetch :token-key :key-dir :cache (atom {}) :now (fn [] ms)
           :ledger :ids (fn [] {...})}, ctx = {:actor <did>}."
  (:require [axxium.domain.github-identity :as gi]
            [axxium.domain.state :as state]
            [axxium.extern.github-app :as gh]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.github-identity :as law]
            [axxium.law.identity-admin :as admin]
            ["node:crypto" :as crypto]))

(defn digest [key] (.digest (.update (crypto/createHash "sha256") key "utf8") "hex"))

(defn event-ids
  "Create fresh identity decision and effect IDs with a shared timestamp."
  []
  {:decision-id (str (random-uuid)) :effect-id (str (random-uuid)) :at (js/Date.)})

(defn authenticate
  "The agent DID when `key` matches its current bootstrap digest and the
   agent and every ancestor are effective; nil otherwise."
  [st did key]
  (let [want (some-> (gi/bootstrap-digest st did) (js/Buffer.from "hex"))
        got (when (string? key) (js/Buffer.from (digest key) "hex"))]
    (when (and want got (= (.-length want) (.-length got)) (crypto/timingSafeEqual want got))
      did)))

(def tools #{"actor_list" "actor_provision" "actor_suspend" "actor_resume" "actor_revoke"
             "credential_provision" "credential_rotate" "credential_revoke" "token_for"
             "bootstrap_issue" "credentials_self" "subtree_export" "subtree_import"})

(def agent-tools
  "What a bootstrap-authenticated host agent may call."
  #{"credentials_self" "token_for" "actor_list"})

(defn- refuse [e] (js/Promise.resolve {:error e}))

(defn- effect-out [{:keys [ledger effect]}]
  (if effect {:ledger ledger :result (select-keys (:event/body effect) [:resource/id :resource/metadata])}
      {:ledger ledger :error :forbidden}))

(defn- secret
  "Seal a PEM to the actor DID, or reference an imported key file."
  [{:keys [token-key]} did {:credential/keys [pem secret key-file]}]
  (cond (and (or pem secret) token-key) {:credential/sealed (tc/seal token-key did (or pem secret))}
        key-file {:credential/key-file key-file}))

(def ^:private public-meta
  [:github/app-id :github/app-slug :github/installation-id
   :credential/provider :credential/env])

(defn- private-key [{:keys [token-key key-dir]} did cred]
  (let [{:credential/keys [sealed key-file]} (:resource/location cred)]
    (cond sealed (some->> sealed (tc/open-sealed token-key did))
          key-file (gh/read-key-file key-dir key-file))))

(defn- ^:async token-for! [{:keys [fetch ids cache now] :or {now #(js/Date.now)} :as deps} lg st caller
                           {:actor/keys [did] :credential/keys [label] :as args}]
  (let [scope (select-keys args [:scope/repos :scope/permissions])
        {lg' :ledger ok? :allow?} (gi/decide-mint lg st caller did label scope (ids))
        cred (gi/usable-credential st did label)
        meta (:resource/metadata cred)]
    (if-not ok?
      {:ledger lg' :error :forbidden}
      (try
        (if-let [pem (private-key deps did cred)]
          (let [signal (js/AbortSignal.timeout 20000)
                app-id (:github/app-id meta)
                jwt (gh/app-jwt app-id pem (now))
                verified (await (gh/verify-installation-scope
                                 fetch jwt app-id (:github/installation-id meta) scope signal))
                permissions (:scope/permissions scope)
                token (await (gh/cached-mint cache (now)
                                            [(:resource/id cred) (:credential/generation meta) app-id verified permissions]
                                            #(gh/mint-installation-token fetch jwt verified permissions signal)))]
            {:ledger lg'
             :result (assoc token :github/actor (str (:github/app-slug meta) "[bot]")
                            :axxium/marker (law/attribution-marker did))})
          {:ledger lg' :error :key-unavailable})
        (catch :default error
          {:ledger lg' :error (or (:axxium/error (ex-data error)) :github-unavailable)})))))

(defn- run [{:keys [ids] :as deps} lg st caller tool a]
  (let [did (:actor/did a) label (:credential/label a)]
    (case tool
      "actor_list" (js/Promise.resolve {:result {:actors (gi/actors-under st caller)}})
      "actor_provision" (js/Promise.resolve
                         (effect-out (gi/provision-actor lg st caller (get a :actor/parent caller)
                                                           did (:actor/name a) (:actor/policy a)
                                                           (or (:actor/kind a) :bot) (ids))))
      "actor_suspend" (js/Promise.resolve (effect-out (gi/suspend lg st caller did (ids))))
      "actor_resume" (js/Promise.resolve (effect-out (gi/resume lg st caller did (ids))))
      "actor_revoke" (js/Promise.resolve (effect-out (gi/revoke-actor lg st caller did (ids))))
      "credential_provision"
      (if-let [s (when-not (= :bootstrap-key (:credential/type a)) (secret deps did a))]
        (js/Promise.resolve
         (effect-out (gi/provision-credential lg st caller did label
                                              (:credential/type a :github-app) s
                                              (select-keys a public-meta) (ids))))
        (refuse :invalid))
      "credential_rotate"
      (js/Promise.resolve
       (effect-out (gi/rotate-credential lg st caller did label
                                         (or (secret deps did a)
                                             (select-keys (:resource/location (gi/credential st did label))
                                                          [:credential/sealed :credential/key-file]))
                                         (select-keys a public-meta) (ids))))
      "credential_revoke" (js/Promise.resolve (effect-out (gi/revoke-credential lg st caller did label (ids))))
      "token_for" (token-for! deps lg st caller a)
      "bootstrap_issue"
      (let [key (str "axb_" (.toString (crypto/randomBytes 32) "base64url"))
            out (gi/issue-bootstrap lg st caller did (digest key) (ids))]
        (js/Promise.resolve
         (if (:effect out)
           {:ledger (:ledger out) :result {:actor/did did :bootstrap/key key}}
           {:ledger (:ledger out) :error :forbidden})))
      "subtree_export" (js/Promise.resolve {:result {:seed (gi/export-subtree st caller)}})
      "subtree_import"
      (let [{:keys [ledger created unchanged refused]}
            (gi/apply-seed lg caller (dissoc (gi/resolve-seed (:seed a) (:host a)) :root) ids)]
        (js/Promise.resolve {:ledger ledger :result {:created created :unchanged unchanged
                                                     :refused refused}}))
      "credentials_self"
      (js/Promise.resolve
       {:result {:actor/did caller
                 :credentials
                 (mapv (fn [c]
                         (let [{:credential/keys [type sealed]} (:resource/location c)]
                           (cond-> (assoc (:resource/metadata c) :credential/type type)
                             (and sealed (not= type :github-app))
                             (assoc :credential/secret (tc/open-sealed (:token-key deps) caller sealed)))))
                       (gi/own-credentials st caller))}}))))

(defn- valid? [tool a]
  (and (map? a)
       (or (#{"actor_list" "credentials_self" "subtree_export"} tool)
           (and (= tool "subtree_import") (map? (:seed a)) (string? (:host a))) (string? (:actor/did a)))
       (or (not= "actor_provision" tool) (not (contains? a :actor/parent)) (string? (:actor/parent a)))
       (or (not (#{"credential_provision" "credential_rotate" "credential_revoke" "token_for"} tool))
           (string? (:credential/label a)))))

(defn call [{:keys [ledger] :as deps} {:keys [actor]} tool args]
  (let [st (state/fold ledger)]
    (-> (cond (nil? actor) (refuse :unauthenticated)
              (not (contains? tools tool)) (refuse :unknown-tool)
              (not (valid? tool args)) (refuse :invalid)
              :else (run deps ledger st actor tool args))
        (.catch (fn [e] {:error (or (:axxium/error (ex-data e)) :github-unavailable)})))))

(defn admin
  "The portal's identity manager: one JSON request from `caller` through
   the same policy-checked tools. Resolves {:status :body :ledger}."
  [deps caller req {:keys [read-edn host]}]
  (let [call-spec (admin/request->call caller req read-edn host)]
    (if (map? call-spec)
      (js/Promise.resolve {:status 400 :body {:error (:error call-spec)}})
      (let [[tool args] call-spec]
        (.then (call deps {:actor caller} tool args)
               (fn [out]
                 (if-let [e (:error out)]
                   {:status (if (#{:forbidden :unauthenticated} e) 403 400)
                    :body {:error (name e)} :ledger (:ledger out)}
                   (let [st (state/fold (or (:ledger out) (:ledger deps)))
                         pols (into {} (map (fn [a] [(:actor/did a) (gi/policy-of st (:actor/did a))]))
                                    (get-in out [:result :actors]))]
                     {:status 200 :ledger (:ledger out)
                      :body (admin/result->view (:op req) (:result out) pr-str pols)}))))))))
