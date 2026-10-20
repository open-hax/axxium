(ns axxium.extern.atproto-oauth
  "AT Protocol OAuth client boundary. The official SDK verifies issuer, account
   DID, PKCE, PAR, and DPoP. OAuth tokens are revoked after identity binding."
  (:require [axxium.config :as cfg]
            [axxium.law.atproto :as atproto-law]
            ["@atproto-labs/simple-store-memory" :refer [SimpleStoreMemory]]
            ["@atproto/oauth-client-node" :refer [NodeOAuthClient]]))

(def ^:private callback-path "/api/auth/atproto/callback")
(def ^:private metadata-path "/api/auth/atproto/client-metadata.json")
(defonce ^:private locks (js/Map.))

(defn ^:async with-local-oauth-lock
  "Serialize SDK session operations by key within this Axxium process."
  [key run]
  (let [prior (.get locks key)
        release (atom nil)
        drained (js/Promise. (fn [resolve _] (reset! release resolve)))]
    ;; Reserve this position before yielding so another caller queues behind it.
    (.set locks key drained)
    (try
      (await prior)
      (await (run))
      (finally
        ;; A rejected operation releases successors without rejecting their gate.
        (@release nil)
        (when (identical? (.get locks key) drained)
          (.delete locks key))))))

(def ^:private oauth-ttl-ms 1800000)
;; Browser state cookie maxAge is 300s (extern.http/set-state-cookie!). The
;; SDK writes authorization state during authorize! before the route can set
;; that cookie, so the store TTL includes a margin for PAR/authorize latency
;; and still stays far below the 30-minute session TTL. sessionStore keeps
;; oauth-ttl-ms because sessions are revoked after binding.
(def ^:private state-cookie-max-age-ms 300000)
(def ^:private auth-state-authorize-margin-ms 60000)
(def auth-state-ttl-ms (+ state-cookie-max-age-ms auth-state-authorize-margin-ms))
(def ^:private oauth-max-entries 1024)

(defn new-oauth-store
  "Bound short-lived SDK sessions in memory (LRU; sessions are revoked right
   after identity binding, so eviction only drops already-finished work)."
  []
  (SimpleStoreMemory. #js {:ttl oauth-ttl-ms :max oauth-max-entries}))

(defn state-store-full-error []
  (ex-info "AT Protocol authorization state store is full"
           {:status 503 :axxium/reason :atproto/state-store-full}))

(defn state-store-full? [error]
  (= :atproto/state-store-full (:axxium/reason (ex-data error))))


(defn- project-dpop-jwk
  "Keep the private JWK members the law contract requires for each kty."
  [jwk]
  (let [kty (or (:kty jwk) (get jwk "kty"))
        get* (fn [k] (or (get jwk k) (get jwk (name k))))]
    (case kty
      "EC" {:kty "EC" :crv (get* :crv) :x (get* :x) :y (get* :y) :d (get* :d)}
      "OKP" {:kty "OKP" :crv (get* :crv) :x (get* :x) :d (get* :d)}
      "RSA" {:kty "RSA" :n (get* :n) :e (get* :e) :d (get* :d)
             :p (get* :p) :q (get* :q) :dp (get* :dp) :dq (get* :dq) :qi (get* :qi)}
      ;; Unknown/missing kty: leave a minimal map so the contract refuses it.
      {:kty kty})))

(defn- oauth-saved-state-claims
  "Project a NodeSavedState JS handle (or CLJ test fixture) into the law contract."
  [value]
  (let [auth (if (map? value)
               (:auth-method value)
               (js->clj (aget value "authMethod") :keywordize-keys true))
        jwk (if (map? value)
              (:dpop-jwk value)
              (js->clj (aget value "dpopJwk") :keywordize-keys true))]
    {:iss (if (map? value) (:iss value) (aget value "iss"))
     :verifier (if (map? value) (:verifier value) (aget value "verifier"))
     :auth-method {:method (or (:method auth) (get auth "method"))
                   :kid (or (:kid auth) (get auth "kid"))}
     :dpop-jwk (project-dpop-jwk jwk)
     :app-state (if (map? value) (:app-state value) (aget value "appState"))}))

(defn- admit-oauth-saved-state!
  "Refuse a state-store value that is outside `axxium.law.atproto/OAuthSavedState`."
  [value]
  (let [claims (oauth-saved-state-claims value)
        ;; Drop nil optional kid for method none; drop nil app-state.
        claims (cond-> claims
                 (nil? (:kid (:auth-method claims)))
                 (update :auth-method dissoc :kid)
                 (nil? (:app-state claims))
                 (dissoc :app-state))]
    (when-not (atproto-law/valid-oauth-saved-state? claims)
      (throw (ex-info "OAuth state-store value is outside its contract"
                      {:contract :axxium.law.atproto/OAuthSavedState})))
    value))

(defn new-oauth-state-store
  "Bounded in-memory authorization state that never evicts a live entry.
   Expired entries are purged first; a new key at capacity is refused with
   `state-store-full-error`, so a burst of public sign-in starts can't push
   out an earlier user's state before its callback arrives. Keys and values
   are admitted only when they satisfy `axxium.law.atproto` store contracts."
  ([] (new-oauth-state-store {}))
  ([{:keys [ttl-ms max now] :or {ttl-ms auth-state-ttl-ms max oauth-max-entries now #(js/Date.now)}}]
   (let [entries (js/Map.)
         live (fn [k]
                (when-let [entry (.get entries k)]
                  (if (< (now) (:expires entry))
                    entry
                    (do (.delete entries k) nil))))
         purge! (fn []
                  (let [t (now)]
                    (.forEach entries (fn [entry k _]
                                        (when-not (< t (:expires entry))
                                          (.delete entries k))))))]
     #js {:get (fn [k]
                 ;; Check the key before lookup so an absent malformed key is a
                 ;; contract violation, not ordinary missing state.
                 (when-not (atproto-law/valid-oauth-state-store-key? k)
                   (.delete entries k)
                   (throw (ex-info "OAuth state-store key failed its contract on read"
                                   {:contract :axxium.law.atproto/OAuthStateStoreKey})))
                 (when-let [entry (live k)]
                   (admit-oauth-saved-state! (:value entry))))
          :set (fn [k value]
                 (when-not (atproto-law/valid-oauth-state-store-key? k)
                   (throw (ex-info "OAuth state-store key is outside its contract"
                                   {:contract :axxium.law.atproto/OAuthStateStoreKey})))
                 (admit-oauth-saved-state! value)
                 (let [already (some? (live k))]
                   (when-not already
                     (when (<= max (.-size entries)) (purge!)))
                   (case (atproto-law/auth-state-capacity-admission
                          (.-size entries) max already)
                     :admit nil
                     :refuse (throw (state-store-full-error))))
                 (.set entries k {:value value :expires (+ (now) ttl-ms)})
                 nil)
          :del (fn [k] (.delete entries k) nil)
          :clear (fn [] (.clear entries) nil)})))

;; ---------------------------------------------------------------------------
;; Per-client start limiter
;; ---------------------------------------------------------------------------

(def ^:private start-limiter-max-clients 4096)

(defn new-start-limiter
  "Sliding-window limiter for public AT Protocol sign-in starts, keyed by
   client. Returns `(admit? client-key)`. Tracked clients are bounded; when the
   table is full of live clients a new client is refused (fail closed) rather
   than evicting another client's window."
  ([] (new-start-limiter {}))
  ([{:keys [window-ms max max-clients now]
     :or {window-ms atproto-law/atproto-start-window-ms
          max atproto-law/atproto-start-max-per-window
          max-clients start-limiter-max-clients
          now #(js/Date.now)}}]
   (let [clients (js/Map.)
         recent (fn [t k]
                  (let [kept (filterv #(< t (+ % window-ms)) (or (.get clients k) []))]
                    (if (seq kept) (.set clients k kept) (.delete clients k))
                    kept))
         purge! (fn [t] (doseq [k (vec (es6-iterator-seq (.keys clients)))] (recent t k)))]
     (fn [client-key]
       (let [t (now)
             k (or client-key "unknown")
             starts (recent t k)]
         (when (and (empty? starts) (<= max-clients (.-size clients)))
           (purge! t))
         (if (and (empty? starts) (<= max-clients (.-size clients)))
           false
           (case (atproto-law/atproto-start-rate-admission (count starts) max)
             :admit (do (.set clients k (conj starts t)) true)
             :refuse false)))))))

(defonce start-limiter (delay (new-start-limiter)))

(defn admit-start?
  "Admit one AT Protocol sign-in start for `client-key` under the process limiter."
  [client-key]
  (@start-limiter client-key))

(defn- metadata []
  (let [base (cfg/get-in-config [:axxium/public-base-url])
        callback (str base callback-path)
        local? (.startsWith base "http://127.0.0.1:")
        client-id (if local?
                    (str "http://localhost/?redirect_uri="
                         (js/encodeURIComponent callback) "&scope=atproto")
                    (str base metadata-path))
        result #js {:client_id client-id
                    :client_name "Axxium"
                    :redirect_uris #js [callback]
                    :grant_types #js ["authorization_code" "refresh_token"]
                    :scope "atproto"
                    :response_types #js ["code"]
                    :application_type (if local? "native" "web")
                    :token_endpoint_auth_method "none"
                    :dpop_bound_access_tokens true}]
    (when-not local? (aset result "client_uri" base))
    result))

(defonce ^:private client
  (delay
    (NodeOAuthClient.
     #js {:clientMetadata (metadata)
          :stateStore (new-oauth-state-store)
          :sessionStore (new-oauth-store)
          :requestLock with-local-oauth-lock})))

(defn client-metadata []
  (js->clj (.-clientMetadata @client) :keywordize-keys true))

(defn ^:async authorize! [identity browser-state]
  (str (await (.authorize @client identity #js {:state browser-state}))))

(defn ^:async callback! [request-url]
  (let [url (js/URL. request-url "http://localhost")
        result (await (.callback @client (.-searchParams url)))]
    {:did (.-did (.-session result))
     :state (.-state result)
     :session (.-session result)}))

(defn ^:async revoke! [oauth-session]
  (await (.signOut oauth-session)))
