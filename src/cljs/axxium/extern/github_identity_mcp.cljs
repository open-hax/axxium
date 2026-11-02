(ns axxium.extern.github-identity-mcp
  "MCP tools over the bot identity tree.

   actor_list            actors visible to the caller (self + descendants)
   actor_provision       create a child actor (policy within caller's)
   actor_suspend/resume  an ancestor pauses a subtree (cascades, G3)
   actor_revoke          terminal
   credential_provision  attach a credential: sealed PEM, or an imported
                         key file left in place (e.g. grokbot-foresight)
   credential_rotate     new secret / installation id, generation + 1
   credential_revoke     terminal
   token_for             mint a scoped installation token (in-memory cache)

   (call deps ctx tool args) -> Promise<{:ledger :result} | {:error ...}>
   deps = {:fetch :token-key :key-dir :cache (atom {}) :now (fn [] ms)
           :ledger :ids (fn [] {...})}, ctx = {:actor <did>}."
  (:require [axxium.domain.github-identity :as gi]
            [axxium.domain.state :as state]
            [axxium.extern.github-app :as gh]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.github-identity :as law]))

(def tools #{"actor_list" "actor_provision" "actor_suspend" "actor_resume" "actor_revoke"
             "credential_provision" "credential_rotate" "credential_revoke" "token_for"})

(defn- refuse [e] (js/Promise.resolve {:error e}))

(defn- effect-out [{:keys [ledger effect]}]
  (if effect {:ledger ledger :result (select-keys (:event/body effect) [:resource/id :resource/metadata])}
      {:ledger ledger :error :forbidden}))

(defn- secret
  "Seal a PEM to the actor DID, or reference an imported key file."
  [{:keys [token-key]} did {:credential/keys [pem key-file]}]
  (cond (and pem token-key) {:credential/sealed (tc/seal token-key did pem)}
        key-file {:credential/key-file key-file}))

(def ^:private public-meta [:github/app-id :github/app-slug :github/installation-id])

(defn- private-key [{:keys [token-key key-dir]} did cred]
  (let [{:credential/keys [sealed key-file]} (:resource/location cred)]
    (cond sealed (some->> sealed (tc/open-sealed token-key did))
          key-file (gh/read-key-file key-dir key-file))))

(defn- token-for! [{:keys [fetch ids cache now] :as deps} lg st caller
                   {:actor/keys [did] :credential/keys [label] :as args}]
  (let [scope (select-keys args [:scope/repos :scope/permissions])
        {lg' :ledger ok? :allow?} (gi/decide-mint lg st caller did label scope (ids))
        cred (gi/usable-credential st did label)
        meta (:resource/metadata cred)]
    (if-not ok?
      (js/Promise.resolve {:ledger lg' :error :forbidden})
      (if-let [pem (private-key deps did cred)]
        (-> (gh/cached-mint cache (now) [(:resource/id cred) (:credential/generation meta) scope]
                            #(gh/mint-installation-token
                              fetch (gh/app-jwt (:github/app-id meta) pem (now))
                              (:github/installation-id meta) scope))
            (.then (fn [t] {:ledger lg'
                            :result (assoc t :github/actor (str (:github/app-slug meta) "[bot]")
                                           :axxium/marker (law/attribution-marker did))})))
        (js/Promise.resolve {:ledger lg' :error :key-unavailable})))))

(defn- run [{:keys [ids] :as deps} lg st caller tool a]
  (let [did (:actor/did a) label (:credential/label a)]
    (case tool
      "actor_list" (js/Promise.resolve {:result {:actors (gi/actors-under st caller)}})
      "actor_provision" (js/Promise.resolve
                         (effect-out (gi/provision-actor lg st caller did (:actor/name a) (:actor/policy a) (ids))))
      "actor_suspend" (js/Promise.resolve (effect-out (gi/suspend lg st caller did (ids))))
      "actor_resume" (js/Promise.resolve (effect-out (gi/resume lg st caller did (ids))))
      "actor_revoke" (js/Promise.resolve (effect-out (gi/revoke-actor lg st caller did (ids))))
      "credential_provision"
      (if-let [s (secret deps did a)]
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
      "token_for" (token-for! deps lg st caller a))))

(defn- valid? [tool a]
  (and (map? a)
       (or (= tool "actor_list") (string? (:actor/did a)))
       (or (not (#{"credential_provision" "credential_rotate" "credential_revoke" "token_for"} tool))
           (string? (:credential/label a)))))

(defn call [{:keys [ledger] :as deps} {:keys [actor]} tool args]
  (let [st (state/fold ledger)]
    (-> (cond (nil? actor) (refuse :unauthenticated)
              (not (contains? tools tool)) (refuse :unknown-tool)
              (not (valid? tool args)) (refuse :invalid)
              :else (run deps ledger st actor tool args))
        (.catch (fn [e] {:error (or (:axxium/error (ex-data e)) :github-unavailable)})))))
