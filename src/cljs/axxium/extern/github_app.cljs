(ns axxium.extern.github-app
  "GitHub App boundary: app JWT (RS256, signed locally), manifest code
   conversion, and installation tokens scoped per request. `fetch` is
   injected so tests never touch the network. Keys and tokens are never
   logged; tokens are cached in memory only, never in the ledger."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            [axxium.law.github-identity :as law]
            [clojure.string :as str]))

(def api "https://api.github.com")

(defn fetch!
  "Run a GitHub HTTP request using the runtime fetch adapter."
  [url options]
  (js/fetch url options))

(defn manifest-create-url
  "GitHub's app creation page, with the encoded callback state."
  [state]
  (str "https://github.com/settings/apps/new?state=" (js/encodeURIComponent state)))

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

(defn- reject-scope! []
  (throw (ex-info "GitHub did not prove the requested installation scope"
                  {:axxium/error :installation-scope-mismatch})))

(defn- valid-id? [id] (and (js/Number.isSafeInteger id) (pos? id)))

(defn- credential-id [id]
  (let [id (if (and (string? id) (re-matches #"[0-9]+" id)) (js/Number id) id)]
    (if (valid-id? id) id (reject-scope!))))

(defn- folded-name [s] (when (string? s) (str/lower-case s)))

(defn- installation-grants-scope? [granted requested]
  (and (map? granted)
       (every? (fn [[permission level]]
                 (let [requested-level (get law/levels level)
                       installed-level (get law/levels (get granted (keyword permission) "none"))]
                   (and requested-level installed-level (<= requested-level installed-level))))
               requested)))

(defn- github-get [fetch url token signal]
  (-> (fetch url #js {:method "GET" :headers (headers (str "Bearer " token))
                      :redirect "error" :signal signal})
      (.then json)))

(defn- github-post [fetch url jwt body signal]
  (-> (fetch url #js {:method "POST" :headers (doto (headers (str "Bearer " jwt))
                                              (aset "content-type" "application/json"))
                     :redirect "error" :signal signal
                     :body (js/JSON.stringify (clj->js body))})
      (.then json)))

(defn- ^:async read-repository-pages [fetch token expected signal]
  (loop [page 1 rows []]
    (let [url (str api "/installation/repositories?per_page=100&page=" page)
          {:keys [total_count repositories]} (await (github-get fetch url token signal))]
      (when-not (and (= expected total_count) (vector? repositories)
                     (= (count repositories) (min 100 (- expected (count rows)))))
        (reject-scope!))
      (let [rows (into rows repositories)]
        (if (= expected (count rows)) rows (recur (inc page) rows))))))

(defn- repository-ids [{:keys [owner-id owner-login names]} rows]
  (when-not (and (vector? rows) (= (count names) (count rows))
                 (every? (fn [{:keys [id owner]}]
                           (and (valid-id? id) (= owner-id (:id owner))
                                (= owner-login (folded-name (:login owner))))) rows))
    (reject-scope!))
  (let [by-name (into {} (map (juxt #(folded-name (:full_name %)) :id)) rows)]
    (when-not (and (= (set names) (set (keys by-name)))
                   (= (count names) (count (set (vals by-name)))))
      (reject-scope!))
    (mapv by-name names)))

(defn- ^:async scoped-token [fetch jwt {:keys [installation-id names] :as scope}
                             selection permissions signal]
  (let [permissions (assoc permissions "metadata" "read")
        expected-permissions (into {} (map (fn [[k v]] [(keyword k) v])) permissions)
        url (str api "/app/installations/" installation-id "/access_tokens")
        result (await (github-post fetch url jwt (assoc selection :permissions permissions) signal))
        {:keys [token expires_at]} result
        granted (:permissions result)]
    (when-not (and (string? token) (not (str/blank? token))
                   (string? expires_at) (js/Number.isFinite (.parse js/Date expires_at))
                   (map? granted) (= "read" (get granted :metadata "read"))
                   (= (dissoc expected-permissions :metadata) (dissoc granted :metadata)))
      (reject-scope!))
    (let [rows (if (contains? result :repositories)
                 (:repositories result)
                 (await (read-repository-pages fetch token (count names) signal)))]
      {:token token :expires-at expires_at :repository-ids (repository-ids scope rows)})))

(defn ^:async verify-installation-scope
  "Verify the installation owner before any mint. Resolve exact repository IDs
   with a name-restricted, metadata-only token confined to this operation."
  [fetch jwt app-id installation-id {:scope/keys [repos permissions]} signal]
  (let [app-id (credential-id app-id)
        installation-id (credential-id installation-id)
        names (vec (distinct (map str/lower-case repos)))
        parts (mapv #(str/split % #"/" 2) names)]
    (when-not (and (<= 1 (count names) 500)
                   (every? (fn [[owner repo]] (and (seq owner) (seq repo))) parts))
      (reject-scope!))
    (let [{:keys [id app_id account target_id target_type suspended_at] granted :permissions}
          (await (github-get fetch (str api "/app/installations/" installation-id) jwt signal))
          owner-login (folded-name (:login account))
          owner-id (:id account)]
      (when-not (and (= installation-id id) (= app-id app_id)
                     (#{"Organization" "User"} target_type)
                     (nil? suspended_at) (valid-id? owner-id) (= owner-id target_id)
                     (installation-grants-scope? granted permissions)
                     (seq owner-login) (every? #(= owner-login (first %)) parts))
        (reject-scope!))
      (let [scope {:installation-id installation-id :owner-id owner-id
                   :owner-login owner-login :names names}
            proof (await (scoped-token fetch jwt scope {:repositories (mapv second parts)} {} signal))]
        (assoc scope :repository-ids (:repository-ids proof))))))

(defn ^:async mint-installation-token
  "Mint with verified immutable repository IDs and admit only a matching result."
  [fetch jwt {:keys [repository-ids names] :as scope} permissions signal]
  (when-not (and (vector? repository-ids) (<= 1 (count repository-ids) 500)
                 (= (count names) (count repository-ids)) (every? valid-id? repository-ids))
    (reject-scope!))
  (let [result (await (scoped-token fetch jwt scope {:repository_ids repository-ids} permissions signal))]
    (when-not (= repository-ids (:repository-ids result)) (reject-scope!))
    (dissoc result :repository-ids)))

(defn read-key-file
  "Read an imported PEM in place, only from under `allowed-dir`."
  [allowed-dir path]
  (when (and (seq allowed-dir) (seq path)
             (str/starts-with? (.realpathSync fs path) (str (.realpathSync fs allowed-dir) "/")))
    (fs/readFileSync path "utf8")))

;; In-memory cache keyed by credential, generation, verified scope and permissions.
(defn cached-mint
  "Return a cached token valid for >60 s more, else mint and cache."
  [cache now-ms k mint!]
  (let [hit (get @cache k)]
    (if (and hit (> (- (.parse js/Date (:expires-at hit)) now-ms) 60000))
      (js/Promise.resolve (assoc hit :cached? true))
      (.then (mint!) (fn [t] (swap! cache assoc k t) t)))))

(defn manifest-json [manifest] (js/JSON.stringify (clj->js manifest)))
