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
                             selection permissions signal on-token]
  (let [permissions (assoc permissions "metadata" "read")
        expected-permissions (into {} (map (fn [[k v]] [(keyword k) v])) permissions)
        url (str api "/app/installations/" installation-id "/access_tokens")
        result (await (github-post fetch url jwt (assoc selection :permissions permissions) signal))
        {:keys [token expires_at]} result
        granted (:permissions result)]
    (when (and on-token (string? token) (not (str/blank? token))) (on-token token))
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
  ([fetch jwt app-id installation-id scope signal]
   (await (verify-installation-scope fetch jwt app-id installation-id scope signal nil)))
  ([fetch jwt app-id installation-id {:scope/keys [repos permissions]} signal
    {:keys [on-metadata-token expected-app-slug candidate-permissions]}]
   (let [app-id (credential-id app-id)
         installation-id (credential-id installation-id)
         names (vec (distinct (map str/lower-case repos)))
         parts (mapv #(str/split % #"/" 2) names)]
     (when-not (and (<= 1 (count names) 500)
                    (every? (fn [[owner repo]] (and (seq owner) (seq repo))) parts))
       (reject-scope!))
     (let [{:keys [id app_id app_slug account target_id target_type suspended_at] granted :permissions}
           (await (github-get fetch (str api "/app/installations/" installation-id) jwt signal))
           owner-login (folded-name (:login account))
           owner-id (:id account)
           candidates (when candidate-permissions
                        (filterv #(installation-grants-scope? granted %) candidate-permissions))]
       (when-not (and (= installation-id id) (= app-id app_id)
                      (#{"Organization" "User"} target_type)
                      (nil? suspended_at) (valid-id? owner-id) (= owner-id target_id)
                      (or (nil? expected-app-slug) (= (folded-name expected-app-slug) (folded-name app_slug)))
                      (installation-grants-scope? granted permissions)
                      (or (nil? candidates) (seq candidates))
                      (seq owner-login) (every? #(= owner-login (first %)) parts))
         (reject-scope!))
       (let [scope {:installation-id installation-id :owner-id owner-id
                    :owner-login owner-login :names names}
             proof (await (scoped-token fetch jwt scope {:repositories (mapv second parts)} {} signal on-metadata-token))]
         (cond-> (assoc scope :repository-ids (:repository-ids proof))
           candidates (assoc :candidate-permissions candidates)))))))

(defn ^:async mint-installation-token
  "Mint with verified immutable repository IDs and admit only a matching result."
  ([fetch jwt scope permissions signal]
   (await (mint-installation-token fetch jwt scope permissions signal nil)))
  ([fetch jwt {:keys [repository-ids names] :as scope} permissions signal on-token]
   (when-not (and (vector? repository-ids) (<= 1 (count repository-ids) 500)
                  (= (count names) (count repository-ids)) (every? valid-id? repository-ids))
     (reject-scope!))
   (let [result (await (scoped-token fetch jwt scope {:repository_ids repository-ids} permissions signal on-token))]
     (when-not (= repository-ids (:repository-ids result)) (reject-scope!))
     (dissoc result :repository-ids))))

(defn ^:async comment-target!
  "Identify the exact GitHub conversation with a one-repository read token.
   Check the PR directly when authorized. Only a 404 permits the separate
   issues read, when that is also authorized."
  [fetch token repo number read-permissions signal]
  (let [repo-url (str api "/repos/" repo)
        issue-url (str repo-url "/issues/" number)
        pr-url (str repo-url "/pulls/" number)
        opts #js {:method "GET" :headers (headers (str "Bearer " token)) :redirect "error" :signal signal}
        pr (when (contains? read-permissions "pull_requests") (await (fetch pr-url opts)))]
    (cond
      (and pr (= 200 (.-status pr)))
      (let [target (await (json pr))]
        (when (and (= number (:number target))
                   (= pr-url (folded-name (:url target)))
                   (= issue-url (folded-name (:issue_url target)))
                   (= repo-url (folded-name (get-in target [:base :repo :url]))))
          :pull-request))

      (and (contains? read-permissions "issues") (or (nil? pr) (= 404 (.-status pr))))
      (let [issue (await (fetch issue-url opts))]
        (when (= 200 (.-status issue))
          (let [target (await (json issue))]
            (when (and (= number (:number target))
                       (= issue-url (folded-name (:url target)))
                       (= repo-url (folded-name (:repository_url target))))
              (if (contains? target :pull_request)
                (when (and (nil? pr) (= pr-url (folded-name (get-in target [:pull_request :url])))) :pull-request)
                :issue)))))
      :else nil)))

(defn- post-diagnostics [response target]
  (law/comment-provider-diagnostics
   {:github/phase :comment-post :github/target-kind target :github/http-status (.-status response)
    :github/provider-request-id (some-> response .-headers (.get "x-github-request-id"))}))

(defn ^:async post-issue-comment!
  "Post exactly once. Keep sanitized response metadata even if receipt decoding
   or verification fails. Never parse a rejection body."
  [fetch token repo number body app-id app-slug target signal]
  (let [path (str "/repos/" repo "/issues/" number)
        response (await (fetch (str api path "/comments")
                               #js {:method "POST"
                                    :headers (doto (headers (str "Bearer " token))
                                               (aset "content-type" "application/json"))
                                    :body (js/JSON.stringify #js {:body body})
                                    :redirect "error" :signal signal}))
        details (post-diagnostics response target)
        status (:github/http-status details)
        outcome (if (contains? #{400 401 403 404 410 422 429} status) :rejected :unverified)
        refused {:outcome outcome :diagnostics details}]
    (if-not (= 201 status)
      refused
      (try
        (let [{:keys [id user html_url issue_url performed_via_github_app] created :body} (await (json response))]
          (if (and (valid-id? id)
                   (= (folded-name issue_url) (folded-name (str api path)))
                   (= body created)
                   (= "Bot" (:type user))
                   (valid-id? (:id user))
                   (= (str (folded-name app-slug) "[bot]") (folded-name (:login user)))
                   (or (nil? performed_via_github_app)
                       (and (= (credential-id app-id) (:id performed_via_github_app))
                            (= (folded-name app-slug) (folded-name (:slug performed_via_github_app)))))
                   (contains? #{(str "https://github.com/" repo "/issues/" number "#issuecomment-" id)
                                (str "https://github.com/" repo "/pull/" number "#issuecomment-" id)}
                              (folded-name html_url)))
            {:outcome :posted :diagnostics details
             :receipt {:github/comment-id id :github/comment-url html_url :github/actor (:login user)
                       :github/actor-id (:id user)}}
            refused))
        (catch :default _ refused)))))

(defn ^:async revoke-installation-token!
  "Best-effort caller-owned cleanup for an internal installation token."
  [fetch token signal]
  (let [response (await (fetch (str api "/installation/token")
                               #js {:method "DELETE" :headers (headers (str "Bearer " token))
                                    :redirect "error" :signal signal}))]
    (= 204 (.-status response))))

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
