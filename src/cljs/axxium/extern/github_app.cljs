(ns axxium.extern.github-app
  "GitHub App boundary: app JWT (RS256, signed locally), manifest code
   conversion, and installation tokens scoped per request. `fetch` is
   injected so tests never touch the network. Keys and tokens are never
   logged; tokens are cached in memory only, never in the ledger."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            [clojure.string :as str]))

(def api "https://api.github.com")

(defn- b64u [s] (.toString (js/Buffer.from s "utf8") "base64url"))

(defn app-jwt
  "A 9-minute app JWT, iat backdated 60 s for clock skew."
  [app-id private-key-pem now-ms]
  (let [now (js/Math.floor (/ now-ms 1000))
        signing-input (str (b64u (js/JSON.stringify #js {:alg "RS256" :typ "JWT"})) "."
                           (b64u (js/JSON.stringify #js {:iat (- now 60) :exp (+ now 540)
                                                          :iss (str app-id)})))
        sig (.toString (crypto/sign "RSA-SHA256" (js/Buffer.from signing-input) private-key-pem)
                       "base64url")]
    (str signing-input "." sig)))

(defn- headers [auth]
  (cond-> #js {"accept" "application/vnd.github+json"
               "x-github-api-version" "2022-11-28"
               "user-agent" "axxium"}
    auth (doto (aset "authorization" auth))))

(defn- json [resp]
  (-> (.json resp)
      (.then (fn [b]
               (if (.-ok resp)
                 (js->clj b :keywordize-keys true)
                 (throw (ex-info "github request failed" {:status (.-status resp)})))))))

(defn convert-manifest
  "Exchange a manifest `code` (one-time, 1 hour) for the new app's
   {:id :slug :pem :client_id ...}. No auth is needed: the code is the proof."
  [fetch code]
  (-> (fetch (str api "/app-manifests/" (js/encodeURIComponent code) "/conversions")
             #js {:method "POST" :headers (headers nil)})
      (.then json)))

(defn- owner-repo-names [repos] (mapv #(second (str/split % #"/" 2)) repos))

(defn mint-installation-token
  "POST /app/installations/:id/access_tokens narrowed to `scope`."
  [fetch jwt installation-id {:scope/keys [repos permissions]}]
  (-> (fetch (str api "/app/installations/" installation-id "/access_tokens")
             #js {:method "POST" :headers (doto (headers (str "Bearer " jwt))
                                            (aset "content-type" "application/json"))
                  :body (js/JSON.stringify
                         (clj->js {:repositories (owner-repo-names repos)
                                   :permissions permissions}))})
      (.then json)
      (.then (fn [{:keys [token expires_at]}] {:token token :expires-at expires_at}))))

(defn read-key-file
  "Read an imported PEM in place, only from under `allowed-dir`."
  [allowed-dir path]
  (when (and (seq allowed-dir) (seq path)
             (str/starts-with? (.realpathSync fs path) (str (.realpathSync fs allowed-dir) "/")))
    (fs/readFileSync path "utf8")))

;; In-memory cache: [credential-id generation scope] -> {:token :expires-at}.
(defn cached-mint
  "Return a cached token valid for >60 s more, else mint and cache."
  [cache now-ms k mint!]
  (let [hit (get @cache k)]
    (if (and hit (> (- (.parse js/Date (:expires-at hit)) now-ms) 60000))
      (js/Promise.resolve (assoc hit :cached? true))
      (.then (mint!) (fn [t] (swap! cache assoc k t) t)))))

(defn manifest-json [manifest] (js/JSON.stringify (clj->js manifest)))
