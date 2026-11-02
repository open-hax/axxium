(ns axxium.routes.github-identity
  "Provision bot actors' own GitHub Apps and expose the identity MCP tools.

   POST /api/github/manifest           session = parent; body {did, name?}
        -> HTML form that POSTs an App manifest to GitHub for that child
   GET  /api/github/manifest/callback  session = same parent; converts the
        one-time code, seals the App PEM to the child DID, registers it
   POST /api/github/mcp                session; {tool, arguments (EDN)}

   The manifest state is a signed 5-minute cookie bound to parent + child,
   so a callback can only attach an App to the child its parent chose."
  (:require [axxium.config :as cfg]
            [axxium.domain.github-identity :as gi]
            [axxium.domain.state :as state]
            [axxium.extern.github-app :as gh]
            [axxium.extern.github-identity-mcp :as mcp]
            [axxium.extern.google :as google]
            [axxium.extern.http :as http]
            [axxium.extern.jose :as jose]
            [axxium.extern.ledger-file :as ledger-file]
            [axxium.extern.signing-key :as signing-key]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.github-identity :as law]
            [axxium.law.google-drive :as did-law]
            [axxium.routes.auth-context :as auth-context]
            [clio.shape.edn :as edn]
            [clojure.string :as str]))

(def ^:private state-cookie "axxium_github_manifest_state")

(defn- base-url [] (cfg/get-in-config [:axxium/public-base-url]))
(defn- secure? [] (str/starts-with? (base-url) "https://"))
(defn- caller [context] (did-law/actor-did (base-url) (:auth/actor-id context)))
(defn- token-key [] (tc/read-key-file (cfg/get-in-config [:github-identity/token-key-file])))
(defn- ledger-path [] (cfg/get-in-config [:github-identity/ledger-file]))
(defn- configured? [] (and (token-key) (seq (ledger-path))))

(defn- state-keys []
  (let [m (signing-key/current!)]
    {:private-key (:private-key m) :public-key (:public-key m)
     :kid (:kid m) :issuer (cfg/get-in-config [:jwt/issuer])}))

(defonce ^:private ledger (atom nil))
(defonce ^:private queue (atom (js/Promise.resolve nil)))
(defonce ^:private token-cache (atom {}))

(defn- current-ledger []
  (or @ledger (reset! ledger (ledger-file/load-ledger (ledger-path)))))

(defn- serialized [f]
  (let [run (.then @queue
                   (fn [_]
                     (let [before (current-ledger)]
                       (.then (f before)
                              (fn [out]
                                (when-let [after (:ledger out)]
                                  (reset! ledger (ledger-file/append-batch! (ledger-path) before after)))
                                out)))))]
    (reset! queue (.catch run (fn [_] nil)))
    run))

(defn- ids [] {:decision-id (str (random-uuid)) :effect-id (str (random-uuid)) :at (js/Date.)})

(defn- deps [lg]
  {:fetch js/fetch :token-key (token-key) :ledger lg :ids ids :cache token-cache
   :now #(js/Date.now) :key-dir (cfg/get-in-config [:github-identity/key-dir])})

(defn- esc [s] (-> s (str/replace "&" "&amp;") (str/replace "\"" "&quot;") (str/replace "<" "&lt;")))

(defn- ^:async manifest! [req reply context]
  (let [parent (caller context)
        child (some-> (http/body req) .-did)
        st (state/fold (current-ledger))]
    (if-not (and (configured?) (string? child) (gi/may-manage? st parent child))
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
                    (str "<form id=f method=post action=\"https://github.com/settings/apps/new?state="
                         (js/encodeURIComponent state) "\"><input type=hidden name=manifest value=\""
                         (esc (gh/manifest-json manifest))
                         "\"><button>Create GitHub App</button></form>"))))))

(defn- ^:async callback! [req reply context]
  (let [state (http/query-param req "state")
        code (http/query-param req "code")
        sealed (http/cookie req state-cookie)
        opened (when (seq sealed) (await (jose/verify-google-state! sealed (state-keys))))
        parent (caller context)
        bound? (and opened (= state (:state opened)) (= parent (:verifier opened)))]
    (http/clear-state-cookie! reply state-cookie law/manifest-callback-path)
    (if-not (and bound? (seq code) (configured?))
      (http/send! reply 400 {:error "Invalid or expired manifest state"})
      (try
        (let [child (:nonce opened)
              app (await (gh/convert-manifest js/fetch code))
              out (await (serialized
                          (fn [lg]
                            (js/Promise.resolve
                             (gi/provision-credential
                              lg (state/fold lg) parent child "github-app" :github-app
                              {:credential/sealed (tc/seal (token-key) child (:pem app))}
                              {:github/app-id (:id app) :github/app-slug (:slug app)}
                              (ids))))))]
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
          tool (some-> body .-tool)
          args (try (edn/read-one (or (some-> body .-arguments) "{}")) (catch :default _ ::bad))]
      (if (= ::bad args)
        (http/send! reply 400 {:error "arguments must be one EDN map"})
        (let [out (await (serialized #(mcp/call (deps %) {:actor (caller context)} tool args)))]
          (http/send-secret! reply (if (:error out) 400 200) {:edn (pr-str (dissoc out :ledger))}))))))

(defn register-github-identity-routes! [app]
  (http/post! app "/api/github/manifest" (auth-context/with-auth manifest!))
  (http/get! app law/manifest-callback-path (auth-context/with-auth callback!))
  (http/post! app "/api/github/mcp" (auth-context/with-auth mcp!)))
