(ns axxium.routes.github-identity
  "Provision bot actors' own GitHub Apps and expose the identity MCP tools.

   POST /api/github/manifest           session = parent; body {did, name?}
        -> HTML form that POSTs an App manifest to GitHub for that child
   GET  /api/github/manifest/callback  session = same parent; converts the
        one-time code, seals the App PEM to the child DID, registers it
   POST /api/github/mcp                session; {tool, arguments (EDN)}
   POST /api/identity/admin            session; the portal's identity manager
        (JSON {op, ...}; see axxium.law.identity-admin)
   POST /api/identity/agent            a host agent as itself:
        x-axxium-actor: <agent did>, authorization: Bearer <bootstrap key>;
        only credentials_self, token_for, actor_list

   The manifest state is a signed 5-minute cookie bound to parent + child,
   so a callback can only attach an App to the child its parent chose."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.domain.github-identity :as gi]
            [axxium.domain.state :as state]
            [axxium.extern.github-app :as gh]
            [axxium.extern.github-identity-mcp :as mcp]
            [axxium.extern.google :as google]
            [axxium.extern.http :as http]
            [axxium.extern.jose :as jose]
            [axxium.extern.ledger-file :as ledger-file]
            [axxium.extern.ledger-queue :as ledger-queue]
            [axxium.extern.signing-key :as signing-key]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.did :as did-law]
            [axxium.law.github-identity :as law]
            [axxium.routes.auth-context :as auth-context]
            [clio.shape.edn :as edn]
            [clojure.string :as str]))

(def ^:private state-cookie "axxium_github_manifest_state")

(defn- base-url [] (cfg/get-in-config [:axxium/public-base-url]))
(defn- secure? [] (str/starts-with? (base-url) "https://"))
(defn- root-binding [] (cfg/get-in-config [:identity/root-operator-binding]))

(defn- ^:async caller [req context st]
  (let [account (:auth/actor-id context)
        canonical (did-law/actor-did (base-url) account)
        {:keys [actor-id root-did]} (root-binding)]
    (cond
      (= actor-id account)
      (when (and (gi/active-human-root? st root-did)
                 (await (session/active-human-session? req context)))
        root-did)

      (not= canonical root-did) canonical)))

(defn- token-key [] (tc/read-key-file (cfg/get-in-config [:github-identity/token-key-file])))
(defn- ledger-path [] (cfg/get-in-config [:github-identity/ledger-file]))
(defn- configured? [] (and (token-key) (seq (ledger-path))))

(defn- state-keys []
  (let [m (signing-key/current!)]
    {:private-key (:private-key m) :public-key (:public-key m)
     :kid (:kid m) :issuer (cfg/get-in-config [:jwt/issuer])}))

(defonce ^:private ledger-writer (ledger-queue/create))
(defonce ^:private token-cache (atom {}))

(defn- current-ledger []
  (ledger-queue/current-ledger ledger-writer (ledger-path)))

(defn- serialized [f]
  (ledger-queue/submit! ledger-writer (ledger-path) f))

(defonce ^:private folded (atom nil))

(defn- fold-once
  "The projection of `lg`, folded once per ledger value. The writer replaces
   the cached ledger on every commit, so `identical?` is exact here and a
   token check no longer re-folds the whole identity ledger."
  [lg]
  (let [[seen st] @folded]
    (if (identical? seen lg)
      st
      (let [st (state/fold lg)]
        (reset! folded [lg st])
        st))))

(defn current-actor-state
  "Read the last committed actor tree without acquiring its writer.

   Authority window (documented, not a lock): this read never waits for the
   identity writer, so it is safe to call from inside `with-current-actor-state!`
   (the mailbox and webhook paths do, through `active-proof!`; taking the writer
   again there would deadlock the queue). Inside that writer no identity write
   can commit, so the read is serialized. Outside it (an OIDC token check or the
   consent re-check after the grant INSERT) a suspension committed after this
   read is seen by the next check; every mailbox effect in directory mode
   re-checks inside the writer, so the stale read cannot authorize a send."
  []
  (fold-once (current-ledger)))

(defn with-current-actor-state!
  "Run f against the admitted actor tree before another identity write can commit."
  [f]
  (serialized (fn ^:async inspect-actor-state [lg]
                {:result (await (f (fold-once lg)))})))

(defn- deps [lg]
  {:fetch gh/fetch! :token-key (token-key) :ledger lg :ids mcp/event-ids :cache token-cache
   :key-dir (cfg/get-in-config [:github-identity/key-dir])})

(defn- esc [s] (-> s (str/replace "&" "&amp;") (str/replace "\"" "&quot;") (str/replace "<" "&lt;")))

(defn- ^:async manifest! [req reply context]
  (let [st (when (configured?) (state/fold (current-ledger)))
        parent (when st (await (caller req context st)))
        child (:did (http/body req))]
    (if-not (and parent (string? child) (gi/may-manage? st parent child))
      (http/send! reply 403 {:error "not a managing ancestor of that actor"})
      (let [state (str (:auth/actor-id context) "." (google/random-secret))
            sealed (await (jose/sign-google-state! {:state state :nonce child :verifier parent}
                                                   (state-keys)))
            node (:resource/metadata (gi/actor-node st child))
            manifest (law/app-manifest {:public-base-url (base-url)
                                        :actor-name (:actor/name node) :policy (:actor/policy node)})]
        (http/set-state-cookie! reply state-cookie sealed (secure?) law/manifest-callback-path)
        (http/set-header! reply "content-type" "text/html; charset=utf-8")
        (http/send! reply 200
                    (str "<form id=f method=post action=\"" (gh/manifest-create-url state)
                         "\"><input type=hidden name=manifest value=\""
                         (esc (gh/manifest-json manifest))
                         "\"><button>Create GitHub App</button></form>"))))))

(defn- ^:async callback! [req reply context]
  (let [state (http/query-param req "state")
        code (http/query-param req "code")
        sealed (http/cookie req state-cookie)
        opened (when (seq sealed) (await (jose/verify-google-state! sealed (state-keys))))
        st (when (and opened (configured?)) (state/fold (current-ledger)))
        parent (when st (await (caller req context st)))
        child (:nonce opened)
        bound? (and parent (= state (:state opened)) (string? state)
                    (str/starts-with? state (str (:auth/actor-id context) "."))
                    (= parent (:verifier opened)) (gi/may-manage? st parent child))]
    (http/clear-state-cookie! reply state-cookie law/manifest-callback-path)
    (if-not (and bound? (seq code))
      (http/send! reply 400 {:error "Invalid or expired manifest state"})
      (try
        (let [app (await (gh/convert-manifest gh/fetch! code))
              out (await (serialized
                          (fn ^:async register-app-credential [lg]
                            (let [st (state/fold lg)]
                              (when (= parent (await (caller req context st)))
                                (gi/provision-credential
                                  lg st parent child "github-app" :github-app
                                  {:credential/sealed (tc/seal (token-key) child (:pem app))}
                                  {:github/app-id (:id app) :github/app-slug (:slug app)}
                                  (mcp/event-ids)))))))]
          (if (:effect out)
            (http/redirect! reply (str "https://github.com/apps/" (:slug app) "/installations/new"))
            (http/send! reply 403 {:error "credential registration refused"})))
        (catch :default err
          (println "GitHub manifest conversion failed:" (:status (ex-data err)))
          (http/send! reply 502 {:error "GitHub manifest conversion failed"}))))))

(defn- ^:async mcp! [req reply context]
  (if-not (configured?)
    (http/send! reply 503 {:error "GitHub identities are not configured on this Axxium"})
    (let [body (http/body req)
          tool (:tool body)
          args (try (edn/read-one (or (:arguments body) "{}")) (catch :default _ ::bad))]
      (if (= ::bad args)
        (http/send! reply 400 {:error "arguments must be one EDN map"})
        (let [out (await (serialized
                          (fn ^:async call-identity-tool [lg]
                            (let [st (state/fold lg)
                                  actor (await (caller req context st))
                                  out (await (mcp/call (deps lg) {:actor actor} tool args))
                                  delivery-error
                                  (when (and (:result out) (= "token_for" tool)
                                             (= actor (:root-did (root-binding))))
                                    (try
                                      (when (not= actor (await (caller req context st)))
                                        :unauthenticated)
                                      (catch :default _ :session-unavailable)))]
                              (if delivery-error
                                (-> out (dissoc :result) (assoc :error delivery-error))
                                out)))))
              status (case (:error out) :unauthenticated 403 :session-unavailable 503 nil 200 400)]
          (http/send-secret! reply status {:edn (pr-str (dissoc out :ledger))}))))))

(defn ^:async agent-operation
  "Authenticate and execute an agent tool against one serialized ledger snapshot."
  [lg did key tool args]
  (let [self (when (not= did (:root-did (root-binding)))
               (mcp/authenticate (state/fold lg) did key))]
    (cond
      (nil? self) {:agent/status 401 :agent/error "unknown agent or bad bootstrap key"}
      (not (contains? mcp/agent-tools tool)) {:agent/status 403 :agent/error "tool not open to agents"}
      (= ::bad args) {:agent/status 400 :agent/error "arguments must be one EDN map"}
      :else (let [out (await (mcp/call (deps lg) {:actor self} tool args))]
              (assoc out :agent/status (if (:error out) 400 200))))))

(defn- ^:async agent! [req reply _context]
  (if-not (configured?)
    (http/send! reply 503 {:error "identities are not configured on this Axxium"})
    (let [did (http/header req "x-axxium-actor")
          key (some-> (http/authorization req) (str/replace #"^Bearer " ""))
          body (http/body req)
          tool (:tool body)
          args (try (edn/read-one (or (:arguments body) "{}")) (catch :default _ ::bad))
          out (await (serialized #(agent-operation % did key tool args)))
          status (:agent/status out)]
      (if-let [error (:agent/error out)]
        (http/send! reply status {:error error})
        (http/send-secret! reply status {:edn (pr-str (dissoc out :ledger :agent/status))})))))

(defn- host [] (str/replace (base-url) #"^https?://" ""))

(defn- ^:async admin! [req reply context]
  (if-not (configured?)
    (http/send! reply 503 {:error "identities are not configured on this Axxium"})
    (let [out (await (serialized
                      (fn ^:async administer-identity [lg]
                        (if-let [actor (await (caller req context (state/fold lg)))]
                          (await (mcp/admin (deps lg) actor (http/body req)
                                            {:read-edn edn/read-one :host (host)}))
                          {:status 403 :body {:error "Identity root requires its configured human operator"}}))))]
      (http/send-secret! reply (:status out) (:body out)))))

(defn ^:async apply-seed-file!
  "AXXIUM_IDENTITY_SEED_FILE: apply a secret-free EDN tree idempotently,
   as its root, through the policy-checked provision op. Existing actors
   are untouched, so restarts are no-ops."
  []
  (when-let [path (not-empty (cfg/get-in-config [:identity/seed-file]))]
    (when (configured?)
      (let [source (edn/read-one (ledger-file/read-text path))
            authority (host)
            out (await (serialized
                        (fn [lg]
                          (let [seed (gi/resolve-seed source authority (state/fold lg))]
                            (gi/apply-seed lg (get-in seed [:root :did]) seed mcp/event-ids)))))]
        (println "Identity seed:" (count (:created out)) "created,"
                 (count (:unchanged out)) "unchanged," (count (:refused out)) "refused")
        out))))

(defn- ^:async apply-seed-safely! []
  (try
    (await (apply-seed-file!))
    (catch :default e
      (println "Identity seed failed:" (ex-message e)))))

(defn register-github-identity-routes! [app]
  (http/post! app "/api/identity/admin" (auth-context/with-auth admin!))
  (apply-seed-safely!)
  (http/post! app "/api/identity/agent" (fn [req reply] (agent! req reply nil)))
  (http/post! app "/api/github/manifest" (auth-context/with-auth manifest!))
  (http/get! app law/manifest-callback-path (auth-context/with-auth callback!))
  (http/post! app "/api/github/mcp" (auth-context/with-auth mcp!)))
