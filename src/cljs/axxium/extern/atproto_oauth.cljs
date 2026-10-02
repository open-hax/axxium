(ns axxium.extern.atproto-oauth
  "AT Protocol OAuth client boundary. The official SDK verifies issuer, account
   DID, PKCE, PAR, and DPoP. OAuth tokens are revoked after identity binding."
  (:require [axxium.config :as cfg]
            ["@atproto/oauth-client-node" :refer [NodeOAuthClient]]))

(def ^:private callback-path "/api/auth/atproto/callback")
(def ^:private metadata-path "/api/auth/atproto/client-metadata.json")

(defn- store []
  (let [entries (js/Map.)]
    #js {:set (fn [key value] (.set entries key value) (js/Promise.resolve))
         :get (fn [key] (js/Promise.resolve (.get entries key)))
         :del (fn [key] (.delete entries key) (js/Promise.resolve))}))

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
          :stateStore (store)
          :sessionStore (store)})))

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
