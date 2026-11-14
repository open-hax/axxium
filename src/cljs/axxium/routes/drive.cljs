(ns axxium.routes.drive
  "Connect a signed-in actor's own Google Drive (drive.file, offline) and
   expose Drive tool names through an application-specific EDN RPC.

   GET  /api/drive/connect   session required; redirects to Google
   GET  /api/drive/callback  session required; seals the refresh token
   POST /api/drive/mcp       session required; {tool, arguments-edn}

   The OAuth state is a signed 5-minute cookie whose state value is bound
   to the connecting actor id, so a callback cannot attach a Drive to a
   different session."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.domain.drive :as drive]
            [axxium.domain.state :as state]
            [axxium.extern.drive-mcp :as drive-mcp]
            [axxium.extern.github-identity-mcp :as agent-auth]
            [axxium.extern.google :as google]
            [axxium.extern.google-drive :as gd]
            [axxium.extern.http :as http]
            [axxium.extern.jose :as jose]
            [axxium.extern.ledger-file :as ledger-file]
            [axxium.extern.ledger-queue :as ledger-queue]
            [axxium.extern.signing-key :as signing-key]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.google-drive :as law]
            [axxium.routes.auth-context :as auth-context]
            [axxium.routes.github-identity :as identity]
            [clio.shape.edn :as edn]
            [clojure.string :as str]))

(def ^:private state-cookie "axxium_drive_state")
(def ^:private actor-state-cookie "axxium_drive_actor_state")
(def ^:private actor-state-prefix "drive-actor.")

(defn- base-url [] (cfg/get-in-config [:axxium/public-base-url]))
(defn- redirect-uri [] (str (base-url) law/callback-path))
(defn- secure? [] (str/starts-with? (base-url) "https://"))
(defn- owner [context] (law/actor-did (base-url) (:auth/actor-id context)))
(defn- token-key [] (tc/read-key-file (cfg/get-in-config [:drive/token-key-file])))
(defn- ledger-path [] (cfg/get-in-config [:drive/ledger-file]))

(defn- configured? []
  (and (google/drive-client) (token-key) (seq (ledger-path))))

(defn- actor-access-enabled? []
  (and (true? (cfg/get-in-config [:drive/actor-access?]))
       (seq (:root-did (cfg/get-in-config [:identity/root-operator-binding])))
       (seq (cfg/get-in-config [:github-identity/ledger-file]))))

(defn- state-keys []
  (let [material (signing-key/current!)]
    {:private-key (:private-key material) :public-key (:public-key material)
     :kid (:kid material) :issuer (cfg/get-in-config [:jwt/issuer])}))

;; One in-process writer; the adapter reloads after uncertain disk writes.
(defonce ^:private ledger-writer (ledger-queue/create))

(defn- serialized
  ([f] (ledger-queue/submit! ledger-writer (ledger-path) f))
  ([f append!] (ledger-queue/submit! ledger-writer (ledger-path) f append!)))

(defn- ids []
  {:decision-id (str (random-uuid)) :effect-id (str (random-uuid)) :at (js/Date.)})

(defn- authorize-url [state verifier]
  (gd/authorize-url law/authorization-endpoint
    (law/authorization-params {:client-id (:client-id (google/drive-client))
                               :redirect-uri (redirect-uri) :state state
                               :challenge (google/pkce-challenge verifier)})))

(defn- ^:async connect! [_req reply context]
  (if-not (configured?)
    (http/send! reply 503 {:error "Google Drive is not configured on this Axxium"})
    (let [state (str (:auth/actor-id context) "." (google/random-secret))
          verifier (google/random-secret)
          sealed (await (jose/sign-google-state!
                         {:state state :nonce (google/random-secret) :verifier verifier}
                         (state-keys)))]
      (http/set-state-cookie! reply state-cookie sealed (secure?) law/callback-path)
      (http/redirect! reply (authorize-url state verifier)))))

(defn- eligible-actor? [st root-did actor-did]
  (drive/eligible-actor? st root-did actor-did (js/Date.)))

(defn- ^:async operator-proof! [req]
  (let [{:keys [actor-id root-did]} (cfg/get-in-config [:identity/root-operator-binding])]
    (when-not (http/authorization req)
      (when-let [proof (await (session/human-session-proof! (http/cookie req session/COOKIE-NAME)))]
        (when (= actor-id (:actor-id proof))
          {:session-id (:cred-id proof) :root-did root-did})))))

(defn- ^:async actor-connect! [req reply]
  (let [{:keys [host origin]} (http/origin-settings (base-url) law/callback-path)
        {:keys [actor-did] :as body} (http/body req)]
    (cond
      (not (and (actor-access-enabled?) (configured?)))
      (http/send-secret! reply 503 {:error "Actor Drive access is not configured"})

      (not (and (= origin (http/header req "origin"))
                (= host (some-> (http/header req "host") str/lower-case))))
      (http/send-secret! reply 403 {:error "Drive enrollment origin refused"})

      (not (law/valid-actor-enrollment? body))
      (http/send-secret! reply 400 {:error "Select one Drive actor"})

      :else
      (if-let [{:keys [root-did] :as proof} (await (operator-proof! req))]
        (if (eligible-actor? (identity/current-actor-state) root-did actor-did)
          (let [state (str actor-state-prefix (google/random-secret))
                verifier (google/random-secret)
                sealed (await (jose/sign-drive-actor-state!
                                (assoc proof :state state :verifier verifier :actor-did actor-did)
                                (state-keys)))]
            (http/set-state-cookie! reply actor-state-cookie sealed (secure?) law/callback-path)
            (http/send-secret! reply 200 {:authorization-url (authorize-url state verifier)}))
          (http/send-secret! reply 403 {:error "Drive enrollment actor refused"}))
        (http/send-secret! reply 401 {:error "A current owner browser session is required"})))))

(defn- ^:async exchange! [code verifier]
  (await (gd/exchange-code gd/timed-fetch
           (assoc (google/drive-client) :redirect-uri (redirect-uri) :code code :verifier verifier))))

(defn- complete-connect! [reply tokens persist!]
  (cond
    (not (law/granted-scopes-ok? (:scope tokens)))
    (http/send! reply 403 {:error "Google granted a scope other than drive.file"})

    (not (seq (:refresh_token tokens)))
    (http/send! reply 502 {:error "Google issued no refresh token"})

    :else (persist! (:refresh_token tokens))))

(defn- ^:async persist-actor-grant! [req reply opened refresh-token]
  (let [{:keys [root-did actor-did]} opened
        expected (select-keys opened [:root-did :session-id])
        operator-id (:actor-id (cfg/get-in-config [:identity/root-operator-binding]))
        outcome (:result
                  (await (identity/with-current-actor-state!
                           (fn [st]
                             (when (eligible-actor? st root-did actor-did)
                               (serialized
                                 (fn ^:async register-actor-drive [lg]
                                   (when (= expected (await (operator-proof! req)))
                                     (drive/connect-actor lg (state/fold lg) st
                                       {:root-did root-did :operator-id operator-id :actor-did actor-did}
                                       (tc/seal (token-key) (drive/credential-aad (drive/actor-owner actor-did)) refresh-token)
                                       (ids))))
                                 ledger-file/append-drive-batch!))))))]
    (if (:effect outcome)
      (http/redirect! reply "/portal/index.html?drive=actor-connected")
      (http/send-secret! reply 403 {:error "Drive enrollment authority no longer current"}))))

(defn- ^:async actor-callback! [req reply state code]
  (let [sealed (http/cookie req actor-state-cookie)]
    (http/clear-state-cookie! reply actor-state-cookie law/callback-path)
    (try
      (let [opened (when (and (actor-access-enabled?) (configured?) (seq sealed))
                     (await (jose/verify-drive-actor-state! sealed (state-keys))))
            {:keys [root-did actor-did]} opened]
        (if-not (and opened (= state (:state opened)) (seq code)
                     (= (select-keys opened [:root-did :session-id]) (await (operator-proof! req)))
                     (eligible-actor? (identity/current-actor-state) root-did actor-did))
          (http/send-secret! reply 400 {:error "Invalid or expired actor Drive connect state"})
          (await (complete-connect! reply (await (exchange! code (:verifier opened)))
                   (partial persist-actor-grant! req reply opened)))))
      (catch :default _
        (http/send-secret! reply 502 {:error "Actor Drive connect failed"})))))

(defn- ^:async legacy-callback! [req reply context state code]
  (let [sealed (http/cookie req state-cookie)
        opened (when (seq sealed) (await (jose/verify-google-state! sealed (state-keys))))
        bound? (and opened (seq state) (= state (:state opened))
                    (str/starts-with? state (str (:auth/actor-id context) ".")))]
    (http/clear-state-cookie! reply state-cookie law/callback-path)
    (if-not (and bound? (seq code) (configured?))
      (http/send! reply 400 {:error "Invalid or expired Drive connect state"})
      (try
        (let [tokens (await (gd/exchange-code gd/timed-fetch (assoc (google/drive-client)
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
                                (drive/connect lg (state/fold lg) did sealed-token (ids)))))]
              (if (:effect out)
                (http/redirect! reply "/portal/index.html?drive=connected")
                (http/send! reply 403 {:error "Drive credential registration refused"})))))
        (catch :default err
          (println "Drive connect failed:" (:status (ex-data err)))
          (http/send! reply 502 {:error "Google Drive connect failed"}))))))

(defn- ^:async callback! [req reply context]
  (let [state (http/query-param req "state")
        code (http/query-param req "code")]
    (if (and (string? state) (str/starts-with? state actor-state-prefix))
      (await (actor-callback! req reply state code))
      (await (legacy-callback! req reply context state code)))))

(defn- ^:async enroll-actor! [req reply _context]
  (try
    (await (actor-connect! req reply))
    (catch :default _
      (http/send-secret! reply 503 {:error "Actor Drive enrollment is unavailable"}))))

(defn- rpc-args [body]
  (try (edn/read-one (or (:arguments body) "{}"))
       (catch :default _ ::bad)))

(defn- call-drive [lg owner tool args]
  (drive-mcp/call {:fetch gd/timed-fetch :token-key (token-key)
                   :client (google/drive-client) :ledger lg :ids ids}
                  {:owner owner} tool args))

(defn- respond-edn! [reply out]
  (http/send-secret! reply (if (:error out) 400 200)
    {:edn (pr-str (dissoc out :ledger))}))

(defn- ^:async mcp! [req reply context]
  (if-not (configured?)
    (http/send! reply 503 {:error "Google Drive is not configured on this Axxium"})
    (let [body (http/body req)
          tool (:tool body)
          args (rpc-args body)]
      (if (= ::bad args)
        (http/send! reply 400 {:error "arguments must be one EDN map"})
        (respond-edn! reply (await (serialized #(call-drive % (owner context) tool args))))))))

(defn- actor-bootstrap? [st root-did actor-did key]
  (and (eligible-actor? st root-did actor-did)
       (= actor-did (agent-auth/authenticate st actor-did key))))

(defn- authorized-drive-step [authorize? {:keys [root-did operator-id actor-did]} append! step]
  (serialized
    (fn [lg]
      (cond
        (not (authorize?)) {:error :unauthenticated}
        (not (drive/actor-connected? (state/fold lg) root-did operator-id actor-did))
        {:error :not-connected}
        :else (step lg)))
    append!))

(defn- ^:async agent! [req reply]
  (try
    (let [{:keys [host origin]} (http/origin-settings (base-url) law/callback-path)
          {:keys [root-did actor-id]} (cfg/get-in-config [:identity/root-operator-binding])
          actor-did (http/header req "x-axxium-actor")
          key (some->> (http/authorization req) (re-matches #"(?i)Bearer (axb_[A-Za-z0-9_-]{43})") second)
          incoming-origin (http/header req "origin")]
      (cond
        (not (and (actor-access-enabled?) (configured?)))
        (http/send-secret! reply 503 {:error "Actor Drive access is not configured"})

        (not (and (= host (some-> (http/header req "host") str/lower-case))
                  (or (nil? incoming-origin) (= origin incoming-origin))))
        (http/send-secret! reply 403 {:error "Drive request origin refused"})

        (not (and key (law/actor-did? actor-did)
                  (actor-bootstrap? (identity/current-actor-state) root-did actor-did key)))
        (http/send-secret! reply 401 {:error "A current Drive actor credential is required"})

        :else
        (let [{:keys [tool] :as body} (http/body req)
              args (rpc-args body)
              authorize? #(actor-bootstrap? (identity/current-actor-state) root-did actor-did key)
              principal {:root-did root-did :operator-id actor-id :actor-did actor-did}
              step! (partial authorized-drive-step authorize? principal)]
          (cond
            (not (contains? drive-mcp/tools tool))
            (http/send-secret! reply 403 {:error "Unknown actor Drive tool"})

            (= ::bad args)
            (http/send-secret! reply 400 {:error "arguments must be one EDN map"})

            :else
            (let [out (if (= "drive_put" tool)
                        (await (drive-mcp/actor-put! {:fetch gd/timed-fetch :token-key (token-key)
                                                     :client (google/drive-client) :ids ids
                                                     :sync-ledger! (partial ledger-file/sync-drive-ledger! (ledger-path))}
                                 principal args (partial step! ledger-file/append-drive-batch!) authorize?))
                        (await (step! nil #(call-drive % (drive/actor-owner actor-did) tool args))))]
              (if (and (not= :unauthenticated (:error out)) (authorize?))
                (respond-edn! reply out)
                (http/send-secret! reply 401 {:error "A current Drive actor credential is required"})))))))
    (catch :default _
      (http/send-secret! reply 503 {:error "Actor Drive is unavailable"}))))

(defn register-drive-routes! [app]
  (http/get! app "/api/drive/connect" (auth-context/with-auth connect!))
  (http/get! app law/callback-path (auth-context/with-auth callback!))
  (http/post! app "/api/drive/mcp" (auth-context/with-auth mcp!))
  (http/post! app "/api/drive/actor/connect" (auth-context/with-auth enroll-actor!))
  (http/post! app "/api/drive/agent" agent!))
