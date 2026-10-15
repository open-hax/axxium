(ns axxium.extern.atproto-oauth
  "AT Protocol OAuth client boundary. The official SDK verifies issuer, account
   DID, PKCE, PAR, and DPoP. OAuth tokens are revoked after identity binding."
  (:require [axxium.config :as cfg]
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

(defn new-oauth-state-store
  "Bounded in-memory authorization state that never evicts a live entry.
   Expired entries are purged first; a new key at capacity is refused with
   `state-store-full-error`, so a burst of public sign-in starts can't push
   out an earlier user's state before its callback arrives."
  ([] (new-oauth-state-store {}))
  ([{:keys [ttl-ms max now] :or {ttl-ms oauth-ttl-ms max oauth-max-entries now #(js/Date.now)}}]
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
     #js {:get (fn [k] (when-let [entry (live k)] (:value entry)))
          :set (fn [k value]
                 (when-not (live k)
                   (when (<= max (.-size entries)) (purge!))
                   (when (<= max (.-size entries))
                     (throw (state-store-full-error))))
                 (.set entries k {:value value :expires (+ (now) ttl-ms)})
                 nil)
          :del (fn [k] (.delete entries k) nil)
          :clear (fn [] (.clear entries) nil)})))

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
