(ns axxium.routes.drive
  "Connect a signed-in actor's own Google Drive (drive.file, offline) and
   expose the Drive MCP tools over HTTP.

   GET  /api/drive/connect   session required; redirects to Google
   GET  /api/drive/callback  session required; seals the refresh token
   POST /api/drive/mcp       session required; {tool, arguments-edn}

   The OAuth state is a signed 5-minute cookie whose state value is bound
   to the connecting actor id, so a callback cannot attach a Drive to a
   different session."
  (:require [axxium.config :as cfg]
            [axxium.domain.drive :as drive]
            [axxium.domain.state :as state]
            [axxium.extern.drive-mcp :as drive-mcp]
            [axxium.extern.google :as google]
            [axxium.extern.google-drive :as gd]
            [axxium.extern.http :as http]
            [axxium.extern.jose :as jose]
            [axxium.extern.ledger-file :as ledger-file]
            [axxium.extern.signing-key :as signing-key]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.google-drive :as law]
            [axxium.routes.auth-context :as auth-context]
            [clio.shape.edn :as edn]
            [clojure.string :as str]))

(def ^:private state-cookie "axxium_drive_state")

(defn- base-url [] (cfg/get-in-config [:axxium/public-base-url]))
(defn- redirect-uri [] (str (base-url) law/callback-path))
(defn- secure? [] (str/starts-with? (base-url) "https://"))
(defn- owner [context] (law/actor-did (base-url) (:auth/actor-id context)))
(defn- token-key [] (tc/read-key-file (cfg/get-in-config [:drive/token-key-file])))
(defn- ledger-path [] (cfg/get-in-config [:drive/ledger-file]))

(defn- configured? []
  (and (google/drive-client) (token-key) (seq (ledger-path))))

(defn- state-keys []
  (let [material (signing-key/current!)]
    {:private-key (:private-key material) :public-key (:public-key material)
     :kid (:kid material) :issuer (cfg/get-in-config [:jwt/issuer])}))

;; One in-process writer: every ledger change is chained, appended as one
;; fsynced batch, then becomes the current value.
(defonce ^:private ledger (atom nil))
(defonce ^:private queue (atom (js/Promise.resolve nil)))

(defn- current-ledger []
  (or @ledger (reset! ledger (ledger-file/load-ledger (ledger-path)))))

(defn- serialized
  "Run (f ledger) -> Promise<{:ledger ...}> exclusively and persist its ledger."
  [f]
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

(defn- ids []
  {:decision-id (str (random-uuid)) :effect-id (str (random-uuid)) :at (js/Date.)})

(defn- ^:async connect! [_req reply context]
  (if-not (configured?)
    (http/send! reply 503 {:error "Google Drive is not configured on this Axxium"})
    (let [state (str (:auth/actor-id context) "." (google/random-secret))
          verifier (google/random-secret)
          sealed (await (jose/sign-google-state!
                         {:state state :nonce (google/random-secret) :verifier verifier}
                         (state-keys)))
          params (law/authorization-params
                  {:client-id (:client-id (google/drive-client)) :redirect-uri (redirect-uri)
                   :state state :challenge (google/pkce-challenge verifier)})]
      (http/set-state-cookie! reply state-cookie sealed (secure?) law/callback-path)
      (http/redirect! reply (gd/authorize-url law/authorization-endpoint params)))))

(defn- ^:async callback! [req reply context]
  (let [state (http/query-param req "state")
        code (http/query-param req "code")
        sealed (http/cookie req state-cookie)
        opened (when (seq sealed) (await (jose/verify-google-state! sealed (state-keys))))
        bound? (and opened (seq state) (= state (:state opened))
                    (str/starts-with? state (str (:auth/actor-id context) ".")))]
    (http/clear-state-cookie! reply state-cookie law/callback-path)
    (if-not (and bound? (seq code) (configured?))
      (http/send! reply 400 {:error "Invalid or expired Drive connect state"})
      (try
        (let [tokens (await (gd/exchange-code js/fetch (assoc (google/drive-client)
                                                              :redirect-uri (redirect-uri)
                                                              :code code
                                                              :verifier (:verifier opened))))
              did (owner context)]
          (cond
            (not (law/granted-scopes-ok? (:scope tokens)))
            (http/send! reply 403 {:error "Google granted a scope other than drive.file"})

            (not (seq (:refresh_token tokens)))
            (http/send! reply 502 {:error "Google issued no refresh token"})

            :else
            (let [sealed-token (tc/seal (token-key) did (:refresh_token tokens))
                  out (await (serialized
                              (fn [lg]
                                (js/Promise.resolve
                                 (drive/connect lg (state/fold lg) did sealed-token (ids))))))]
              (if (:effect out)
                (http/redirect! reply "/portal/index.html?drive=connected")
                (http/send! reply 403 {:error "Drive credential registration refused"})))))
        (catch :default err
          (println "Drive connect failed:" (:status (ex-data err)))
          (http/send! reply 502 {:error "Google Drive connect failed"}))))))

(defn- ^:async mcp! [req reply context]
  (if-not (configured?)
    (http/send! reply 503 {:error "Google Drive is not configured on this Axxium"})
    (let [body (http/body req)
          tool (some-> body .-tool)
          args (try (edn/read-one (or (some-> body .-arguments) "{}")) (catch :default _ ::bad))]
      (if (= ::bad args)
        (http/send! reply 400 {:error "arguments must be one EDN map"})
        (let [out (await (serialized
                          (fn [lg]
                            (drive-mcp/call {:fetch js/fetch :token-key (token-key)
                                             :client (google/drive-client)
                                             :ledger lg :ids ids}
                                            {:owner (owner context)} tool args))))]
          ;; EDN text in and out keeps values (and float vectors) lossless.
          (http/send! reply (if (:error out) 400 200)
                      {:edn (pr-str (dissoc out :ledger))}))))))

(defn register-drive-routes! [app]
  (http/get! app "/api/drive/connect" (auth-context/with-auth connect!))
  (http/get! app law/callback-path (auth-context/with-auth callback!))
  (http/post! app "/api/drive/mcp" (auth-context/with-auth mcp!)))
