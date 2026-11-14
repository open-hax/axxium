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
   github_comment        post one issue or PR conversation comment through the actor's App
   subtree_export        the caller's descendants as secret-free seed EDN
   subtree_import        idempotently create missing actors from seed EDN
   bootstrap_issue       ancestor issues/rotates an agent's device key;
                         the plaintext is returned once, only a digest kept
   credentials_self      the caller's own unsealed :api-key / :ssh-key /
                         :github-pat secrets and :github-app metadata

   Host-side agents call with ctx {:actor <own did>} established by
   `authenticate` (did + bootstrap key), so they never hold a parent's
   secrets and can only access credentials and policy-admitted tools as themselves.

   (call deps ctx tool args) -> Promise<{:ledger :result} | {:error ...}>
   deps = {:fetch :token-key :key-dir :cache (atom {}) :now (fn [] ms)
           :ledger :ids (fn [] {...})}, ctx = {:actor <did>}."
  (:require [axxium.domain.github-comment :as comment]
            [axxium.domain.github-identity :as gi]
            [axxium.domain.state :as state]
            [axxium.extern.github-app :as gh]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.github-identity :as law]
            [axxium.law.identity-admin :as admin]
            [clojure.string :as str]
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
    (when (and want got (= (.-length want) (.-length got)) (crypto/timingSafeEqual want got)
               (gi/live? st did (js/Date.)))
      did)))

(def tools #{"actor_list" "actor_provision" "actor_suspend" "actor_resume" "actor_revoke" "github_comment"
             "credential_provision" "credential_rotate" "credential_revoke" "token_for"
             "bootstrap_issue" "credentials_self" "subtree_export" "subtree_import"
             "mailbox_bind" "mailbox_unbind"
             "session_open" "session_close" "actor_advertise" "actor_withdraw" "actor_find"})

(def agent-tools
  "What a bootstrap-authenticated host agent may call."
  #{"credentials_self" "token_for" "actor_list" "github_comment"
    "session_open" "session_close" "actor_advertise" "actor_withdraw" "actor_find"})

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

(defn- ^:async revoke-comment-token! [fetch token]
  (when token
    (try
      (await (gh/revoke-installation-token! fetch token (js/AbortSignal.timeout 2000)))
      (catch :default _ false))))

(defn- ^:async deliver-comment!
  [{:keys [fetch ids checkpoint! reauthorize!] :as deps} lg st caller request fingerprint credential]
  (let [{:actor/keys [did] :github/keys [repo number body request-id]} request
        {app-id :github/app-id app-slug :github/app-slug installation :github/installation-id}
        (:resource/metadata credential)
        metadata-token (volatile! nil)
        write-token (volatile! nil)
        intended? (volatile! false)
        signal (js/AbortSignal.timeout 20000)]
    (try
      (if-let [pem (when (and (string? app-slug) (re-matches #"[A-Za-z0-9-]+" app-slug))
                    (private-key deps did credential))]
        (let [jwt (gh/app-jwt app-id pem (js/Date.now))
              scope (await (gh/verify-installation-scope fetch jwt app-id installation
                                                         (comment/scope repo) signal
                                                         {:expected-app-slug app-slug
                                                          :on-metadata-token #(vreset! metadata-token %)}))
              {:keys [token]} (await (gh/mint-installation-token fetch jwt scope comment/comment-permissions signal
                                                                 #(vreset! write-token %)))]
          (if-not (and (await (reauthorize!))
                       (comment/eligible-credential st caller request (:at (ids))))
            {:error :unauthenticated}
            (let [{:keys [ledger effect]} (comment/decision lg st caller request fingerprint true (ids))]
              (checkpoint! ledger)
              (vreset! intended? true)
              (or (when (and (await (reauthorize!))
                             (comment/eligible-credential st caller request (:at (ids))))
                    (when-let [receipt (await (gh/post-issue-comment! fetch token repo number body app-id app-slug signal))]
                      (let [result (assoc receipt :github/request-id request-id :github/repo repo :github/number number
                                          :actor/did did)]
                        (checkpoint! (comment/record-receipt ledger caller effect result (ids)))
                        {:result result})))
                  {:error :comment-outcome-unknown}))))
        {:error :key-unavailable})
      (catch :default error
        {:error (if @intended? :comment-outcome-unknown
                    (or (:axxium/error (ex-data error)) :github-unavailable))})
      (finally
        (await (js/Promise.all #js [(revoke-comment-token! fetch @metadata-token)
                                   (revoke-comment-token! fetch @write-token)]))))))

(defn- ^:async github-comment!
  "Execute one bounded managed comment with durable, no-replay intent."
  [{:keys [ids checkpoint! reauthorize!] :as deps} lg st caller input]
  (if-not (and checkpoint! reauthorize!)
    {:error :comment-unavailable}
    (let [request (-> input (update :github/repo str/lower-case) (update :github/request-id str/lower-case))
          fingerprint (digest (pr-str (into (sorted-map) request)))
          prior (comment/previous-operation lg request)
          credential (comment/eligible-credential st caller request (:at (ids)))]
      (cond
        (not credential)
        {:error :forbidden :ledger (:ledger (comment/decision lg st caller request fingerprint false (ids)))}

        (and prior (not= fingerprint (:fingerprint prior))) {:error :comment-request-conflict}
        prior (if (await (reauthorize!))
                (if-let [receipt (:receipt prior)] {:result receipt} {:error :comment-outcome-unknown})
                {:error :unauthenticated})
        :else (await (deliver-comment! deps lg st caller request fingerprint credential))))))

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
      "session_open"
      ;; S1: the child's own key is shown once; cred(s) = attenuate(cred(caller)).
      ;; S2: unless :session/advertise? false, the session advertises itself at
      ;; its parent in the same step, with :advert/tags.
      (let [{:keys [at] :as opened-ids} (ids)
            slug (str "s-" (subs (.toString (crypto/randomBytes 8) "hex") 0 12))
            session (str caller ":" slug)
            lease (js/Date. (+ (inst-ms at) (min law/max-lease-ms (or (:session/lease-ms a) 3600000))))
            opened (gi/open-session lg st caller caller session (or (:actor/name a) slug)
                                    (or (:actor/policy a) (gi/policy-of st caller)) lease at opened-ids)
            key (str "axb_" (.toString (crypto/randomBytes 32) "base64url"))
            issued (when (:effect opened)
                     (gi/issue-bootstrap (:ledger opened) (state/fold (:ledger opened)) caller session
                                         (digest key) (ids)))
            advertised (when (and (:effect issued) (not (false? (:session/advertise? a))))
                         (let [{adv-at :at :as adv-ids} (ids)]
                           (gi/advertise (:ledger issued) (state/fold (:ledger issued)) caller session caller
                                         (set (:advert/tags a)) adv-at adv-ids)))]
        ;; Advertising is best-effort around a committed open: a refused advert
        ;; never loses the issued key, so the session stays usable and the
        ;; result reports it as unadvertised.
        (js/Promise.resolve
         (cond
           (not (:effect issued)) {:ledger (:ledger opened) :error :forbidden}
           (and advertised (not (:effect advertised)))
           {:ledger (:ledger issued)
            :result {:actor/did session :actor/lease-until lease :bootstrap/key key
                     :advert/error :forbidden}}
           :else {:ledger (:ledger (or advertised issued))
                  :result (cond-> {:actor/did session :actor/lease-until lease :bootstrap/key key}
                            advertised (assoc :advert/anchor caller))})))
      "session_close"
      (js/Promise.resolve
       (if (= :session (gi/kind-of st did))
         (effect-out (gi/revoke-actor lg st caller did (ids)))
         {:ledger lg :error :forbidden}))
      "actor_advertise"
      (let [{:keys [at] :as advert-ids} (ids)
            target (or did caller)]
        (js/Promise.resolve
         (effect-out (gi/advertise lg st caller target (or (:advert/anchor a) target)
                                   (set (:advert/tags a)) at advert-ids))))
      "actor_withdraw" (js/Promise.resolve (effect-out (gi/withdraw lg st caller (or did caller) (ids))))
      "actor_find"
      (js/Promise.resolve
       {:result {:actors (gi/find-actors st caller (:advert/anchor a) (:query a) (:at (ids)))}})
      "mailbox_bind" (js/Promise.resolve
                      (effect-out (gi/bind-mailbox lg st caller (:mailbox/did a) did (ids))))
      "mailbox_unbind" (js/Promise.resolve
                        (effect-out (gi/unbind-mailbox lg st caller (:mailbox/did a) (ids))))
      "token_for" (token-for! deps lg st caller a)
      "github_comment" (github-comment! deps lg st caller a)
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
            (gi/apply-seed lg caller (gi/resolve-seed (dissoc (:seed a) :root) (:host a) st) ids)]
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
       (or (#{"actor_list" "credentials_self" "subtree_export" "actor_advertise" "actor_withdraw"} tool)
           (and (= tool "session_open")
                (or (nil? (:session/lease-ms a)) (pos-int? (:session/lease-ms a)))
                (or (nil? (:actor/name a)) (string? (:actor/name a)))
                (or (nil? (:actor/policy a)) (law/valid-policy? (:actor/policy a)))
                (or (nil? (:session/advertise? a)) (boolean? (:session/advertise? a)))
                (or (nil? (:advert/tags a))
                    (and (coll? (:advert/tags a)) (law/valid-tags? (set (:advert/tags a))))))
           (and (= tool "actor_find") (string? (:advert/anchor a))
                (or (nil? (:query a)) (string? (:query a))))
           (and (= tool "subtree_import") (map? (:seed a)) (string? (:host a)))
           (and (= tool "mailbox_unbind") (string? (:mailbox/did a)))
           (string? (:actor/did a)))
       (or (not= "mailbox_bind" tool) (string? (:mailbox/did a)))
       (or (not= "actor_provision" tool) (not (contains? a :actor/parent)) (string? (:actor/parent a)))
       (or (not (#{"credential_provision" "credential_rotate" "credential_revoke" "token_for"} tool))
           (string? (:credential/label a)))))

(defn call [{:keys [ledger] :as deps} {:keys [actor]} tool args]
  (let [st (state/fold ledger)]
    (-> (js/Promise.resolve)
        (.then (fn []
                 (cond (nil? actor) (refuse :unauthenticated)
                       (not (contains? tools tool)) (refuse :unknown-tool)
                             (and (= tool "github_comment") (not (comment/valid-request? args))) (refuse :invalid)
                       (not (valid? tool args)) (refuse :invalid)
                       :else (run deps ledger st actor tool args))))
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
