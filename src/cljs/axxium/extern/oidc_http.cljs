(ns axxium.extern.oidc-http
  "Fastify interaction transport around the durable OIDC engine."
  (:require [axxium.auth.session :as session]
            [axxium.extern.agent-credentials :as secrets]
            [axxium.extern.oidc-provider :as engine]
            [axxium.extern.oidc-store :as store]
            [axxium.extern.relay :as relay]
            [axxium.law.oidc :as law]
            [axxium.law.principal :as principal]
            [clojure.string :as str]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]))

(defn- private-config
  "Load only an operator-selected private regular JSON file; never echo values."
  [path]
  (let [fd (.openSync fs path (bit-or (.. fs -constants -O_RDONLY) (.. fs -constants -O_NOFOLLOW)))]
    (try
      (let [stat (.fstatSync fs fd)]
        (when-not (and (.isFile stat) (<= (.-size stat) 1048576)
                       (= 384 (bit-and (.-mode stat) 511)))
          (throw (ex-info "OIDC configuration requires a mode0600 regular file" {})))
        (let [bytes (js/Buffer.alloc 1048577)
              length (.readSync fs fd bytes 0 1048577 0)]
          (when (> length 1048576)
            (throw (ex-info "OIDC configuration exceeds size limit" {})))
          (js->clj (js/JSON.parse (.toString (.subarray bytes 0 length) "utf8")) :keywordize-keys true)))
      (catch :default _ (throw (ex-info "Invalid private OIDC configuration" {})))
      (finally (.closeSync fs fd)))))

(defn- cookie-value
  "Decode one host cookie; reject ambiguity and malformed escapes."
  [request name]
  (let [matches (->> (str/split (or (.. request -headers -cookie) "") #";")
                     (map str/trim)
                     (filter #(str/starts-with? % (str name "="))))]
    (when (= 1 (count matches))
      (try (js/decodeURIComponent (subs (first matches) (inc (count name))))
           (catch :default _ nil)))))

(defn- html-escape
  "Encode untrusted client/interaction labels as plain HTML text."
  [value]
  (str/escape (str value) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn- csrf-value
  "Bind the form to this interaction and the configured actor at rendering."
  [key seed uid bound-actor-did]
  (-> (.createHmac crypto "sha256" key)
      (.update (if bound-actor-did (pr-str [seed uid bound-actor-did]) (str seed ":" uid)))
      (.digest "base64url")))

(defn- csrf-valid?
  "Require exact Origin plus constant-time comparison of bounded ASCII tokens."
  [request issuer-origin cookie-name key uid bound-actor-did]
  (let [seed (cookie-value request cookie-name)
        body (.-body request)
        supplied (when body (aget body "csrf"))]
    (and (= issuer-origin (.. request -headers -origin))
         (string? seed) (re-matches #"[A-Za-z0-9_-]{43}" seed)
         (string? supplied) (re-matches #"[A-Za-z0-9_-]{43}" supplied)
         (.timingSafeEqual crypto
           (js/Buffer.from supplied "utf8")
           (js/Buffer.from (csrf-value key seed uid bound-actor-did) "utf8")))))

(defn- ^:async current-root [request cookie-name resolve-session!]
  (when-let [token (cookie-value request cookie-name)]
    ;; Agent credentials are not browser-root sessions.
    (when-not (str/starts-with? token "axx_")
      (when-let [actor (await (resolve-session! token))]
        (when (= "active" (:status actor)) {:actor actor :digest (secrets/token-hash token)})))))

(defn- ^:async owner-interaction-root!
  [pool token {:keys [owner-actor-id mailbox-did authorized? resolve-human-session!]}]
  (when-let [{:keys [actor actor-id] :as proof} (await (resolve-human-session! token))]
    (when (and (= actor-id owner-actor-id) (authorized?))
      (when-let [work-account (await (store/current-mailbox-account! pool mailbox-did))]
        (when (not= owner-actor-id work-account)
          {:actor actor :work-account work-account :owner-proof (dissoc proof :actor)})))))

(defn- ^:async interaction-root!
  [pool request cookie-name resolve-session! mailbox? owner delegation]
  (cond
    delegation (await (owner-interaction-root! pool (cookie-value request cookie-name) delegation))
    mailbox?
    (when-let [proof (await (session/atproto-session-proof (cookie-value request cookie-name)))]
      (when (and (= owner (principal/address (:principal proof)))
                 (await (session/active-atproto-session? proof)))
        {:actor {:id (:actor-id proof) :display_name owner} :proof proof}))
    :else (await (current-root request cookie-name resolve-session!))))

(defn- owner-authority-error []
  (doto (js/Error. "The owner no longer controls this Work actor")
    (aset "statusCode" 403)))

(defn- ^:async bind-interaction-grant!
  [pool config root grant-id subject bound-actor-did delegation]
  (let [{:keys [mailbox-client-id mailbox-resource]} config]
    (cond
      delegation
      (do
        (when-not ((:authorized? delegation)) (throw (owner-authority-error)))
        (await (store/bind-delegated-mailbox-session! pool grant-id (:work-account root) (:owner-proof root)
                 mailbox-client-id mailbox-resource delegation))
        ;; Do not hold the identity writer across the grant INSERT. An authority
        ;; change during the INSERT must leave no grant to finish the interaction.
        (when-not ((:authorized? delegation))
          (await (store/revoke-family! pool grant-id))
          (throw (owner-authority-error))))

      (:proof root)
      (await (store/bind-mailbox-session! pool grant-id (:proof root)
               mailbox-client-id mailbox-resource bound-actor-did))

      :else
      (await (store/bind-session! pool grant-id subject (:digest root))))))

(defn- fail!
  "Return a generic refusal without disclosing protocol codes or private state."
  [reply status]
  (.header reply "Cache-Control" "no-store")
  (.send (.code reply status) #js {:error "Shared login could not be completed"}))

(defn- html!
  "Render an origin-bound form without scripts, framing or response caching."
  [reply uid client-id actor-label token mailbox? bound-actor-did delegated? inbox? redirect-source]
  (.header reply "Cache-Control" "no-store")
  ;; Keep the issuer Origin on the form POST while withholding cross-origin Referer.
  (.header reply "Referrer-Policy" (if mailbox? "same-origin" "no-referrer"))
  (.header reply "Content-Security-Policy"
    (str "default-src 'none'; form-action 'self'"
         (when redirect-source (str " " redirect-source))
         "; base-uri 'none'; frame-ancestors 'none'"))
  (.type reply "text/html; charset=utf-8")
  (.send reply (str "<!doctype html><html><head><meta charset=utf-8><title>Axxium shared login</title></head><body>"
                    "<h1>Continue with your Axxium identity</h1><p>Signed in as " (html-escape actor-label) ".</p>"
                    "<p>Allow " (if mailbox? "mailbox access for " "sign-in to ")
                    (html-escape client-id) "?</p>"
                    (when bound-actor-did
                      (str "<p>This client may discover eligible actors controlled by your human account and send them mailbox messages as "
                           (html-escape bound-actor-did) ".</p>"))
                    (when inbox?
                      "<p>This client may read and acknowledge messages in the Work actor’s own inbox.</p>")
                    (if delegated?
                      "<p>The Work grant lasts at most 24 hours, including after you sign out. Disconnect the client or remove the Work actor’s authority to end access sooner.</p>"
                      "<p>This sign-in may renew while your current Axxium root session is active, for at most 24 hours. Signing out of Axxium or disabling the actor ends reuse. This does not grant offline access or machine federation permission.</p>")
                    "<form method=post action='/oidc/interaction/" (html-escape uid) "'>"
                    "<input type=hidden name=csrf value='" (html-escape token) "'>"
                    "<button name=decision value=approve>Continue</button>"
                    "<button name=decision value=deny>Cancel</button></form></body></html>")))


(defn- ^:async dispatch-engine!
  "Preserve the mount path and consume only trusted proxy protocol/host values."
  [callback issuer request reply url]
  (.hijack reply)
  (let [raw (.-raw request)
        original-url (.-url raw)
        prior-original-url (.-originalUrl raw)]
    (aset (.-headers raw) "x-forwarded-proto" (.-protocol request))
    (aset (.-headers raw) "x-forwarded-host" (.-host (js/URL. issuer)))
    (set! (.-originalUrl raw) url)
    (set! (.-url raw) (subs url 5))
    (try (await (callback raw (.-raw reply)))
         (finally
           (set! (.-url raw) original-url)
           (set! (.-originalUrl raw) prior-original-url)))))

(def ^:private metadata-aliases
  {"/.well-known/oauth-authorization-server/oidc" "/oidc/.well-known/oauth-authorization-server"
   "/.well-known/openid-configuration/oidc" "/oidc/.well-known/openid-configuration"})

(defn- mailbox-interaction? [config details]
  (law/mailbox-client? config (.. details -params -client_id)))

(defn- mailbox-redirect-source
  "Allow the browser to finish this mailbox interaction's registered callback redirect."
  [{:keys [clients mailbox-client-id]} details]
  (let [redirect (.. details -params -redirect_uri)
        registered (some #(when (= mailbox-client-id (:id %)) (:redirect-uris %)) clients)]
    (when (some #{redirect} registered)
      (let [url (js/URL. redirect)]
        ;; Browsers check the origin across redirects. The provider still checks the exact URI.
        (when (re-matches #"[A-Za-z0-9.-]+(?::[0-9]+)?" (.-host url))
          (.-origin url))))))

(defn- mailbox-resource? [config details]
  (let [actual (some-> details (aget "params") (aget "resource"))]
    (or (= (:mailbox-resource config) actual)
        (and (.isArray js/Array actual)
             (= [(:mailbox-resource config)] (vec (array-seq actual)))))))

(defn- install-store-sweeper!
  "Own the bounded protocol-retention timer for this server lifecycle only."
  [app pool]
  (let [running? (atom false)
        timer (js/setInterval
                (fn ^:async sweep-protocol-records []
                  (when-not @running?
                    (reset! running? true)
                    (try
                      (await (store/cleanup-expired! pool))
                      (catch :default _
                        ;; Database/parser details can contain protocol payloads.
                        (.warn (.-log app) "OIDC retention sweep failed"))
                      (finally (reset! running? false)))))
                60000)]
    (.unref timer)
    (.addHook app "onClose"
      (fn ^:async stop-store-sweeper [_instance]
        (js/clearInterval timer)))))

(defn ^:async install!
  "Optionally mount an operator-configured authority at the fixed /oidc issuer.
   Root session and actor resolution remain owned by Axxium, not by this UI."
  [app pool {:keys [config-file public-base-url session-cookie-name mailbox-actor-did mailbox-bound-actor-did
                     mailbox-available? mailbox-owner-authority]}
   resolve-session! lookup-actor!]
  (when (seq config-file)
    (let [config (private-config config-file)
          issuer (:issuer config)
          mailbox-resource (:mailbox-resource config)
          actor-messaging? (true? (:mailbox-actor-messaging? config))
          inbox-enabled? (true? (:mailbox-inbox? config))
          delegation (when (:mailbox-owner-consent? config)
                       (assoc mailbox-owner-authority
                         :mailbox-did mailbox-actor-did :bound-actor-did mailbox-bound-actor-did))
          csrf-actor (cond
                       delegation (cond-> [mailbox-bound-actor-did mailbox-actor-did
                                           (:owner-actor-id delegation) (:root-did delegation)]
                                    inbox-enabled? (conj law/mailbox-inbox-scope))
                       actor-messaging? mailbox-bound-actor-did)
          resource-scopes (law/mailbox-resource-scopes config)
          origin (.-origin (js/URL. issuer))
          secure? (= "https:" (.-protocol (js/URL. issuer)))
          csrf-cookie (if secure? "__Host-axxium_oidc_csrf" "axxium_oidc_fixture_csrf")
          csrf-key (first (:cookie-keys config))]
      (when-not (= issuer (str (str/replace public-base-url #"/$" "") "/oidc"))
        (throw (ex-info "OIDC issuer must be the configured primary origin at /oidc" {})))
      (when (and mailbox-resource
                 (not (and mailbox-available? (seq mailbox-actor-did)
                           (= mailbox-resource (str (str/replace public-base-url #"/$" "") "/api/mailbox/mcp")))))
        (throw (ex-info "OIDC mailbox grant requires the configured recipient and exact mailbox resource" {})))
      (when (and actor-messaging? (not (law/mailbox-bound-actor-did? mailbox-bound-actor-did)))
        (throw (ex-info "OIDC mailbox actor messaging requires a configured seeded actor DID" {})))
      (when (and delegation
                 (not (and actor-messaging? (seq (:owner-actor-id delegation))
                           (law/mailbox-bound-actor-did? (:root-did delegation))
                           (not= (:root-did delegation) mailbox-bound-actor-did)
                           (fn? (:authorized? delegation)) (fn? (:resolve-human-session! delegation)))))
        (throw (ex-info "OIDC Work owner consent requires the canonical root operator and managed actor" {})))
      (await (store/initialize! pool))
      (install-store-sweeper! app pool)
      (let [verify-grant! (fn [grant-id actor-id] (store/session-grant-active? pool grant-id actor-id))
            provider (if delegation
                       (engine/create-delegated-provider! pool config lookup-actor! verify-grant! mailbox-actor-did delegation)
                       (engine/create-provider! pool config lookup-actor! verify-grant! mailbox-actor-did))
            callback (.callback provider)]
        (await (relay/install! app config #(private-config config-file) lookup-actor!))
        ;; Provider receives the raw stream before Fastify can parse a token body.
        (set! (.-proxy provider) true)
        (.addHook app "onRequest"
          (fn ^:async oidc-dispatch [request reply]
            (let [url (.. request -raw -url)
                  path (first (str/split url #"\?" 2))
                  verification? (or (= path "/oidc/device")
                                    (and (str/starts-with? path "/oidc/device/")
                                         (not= path "/oidc/device/auth")))]
              (when-let [engine-url (or (when (str/starts-with? url "/oidc/") url)
                                        (when (and mailbox-resource (= "GET" (.-method request)))
                                          (get metadata-aliases path)))]
                (cond
                  (and secure? (not= "https" (.-protocol request)))
                  (fail! reply 503)

                  verification?
                  ;; These browser routes use the engine's session-bound XSRF
                  ;; forms, plus Axxium root-session and exact Origin admission.
                  ;; Device authorization and token polling are machine traffic.
                  (cond
                    (and (not= "GET" (.-method request))
                         (not= origin (.. request -headers -origin)))
                    (fail! reply 403)

                    (nil? (await (current-root request session-cookie-name resolve-session!)))
                    (if (= "GET" (.-method request))
                      (.redirect reply (str "/portal/index.html?oidc_return="
                                            (js/encodeURIComponent
                                              (law/device-login-return url))))
                      (fail! reply 401))

                    :else (await (dispatch-engine! callback issuer request reply engine-url)))

                  (not (str/starts-with? url "/oidc/interaction/"))
                  (await (dispatch-engine! callback issuer request reply engine-url)))))))
        (.register app
          (fn ^:async register-interaction-routes [routes]
            (.addContentTypeParser routes "application/x-www-form-urlencoded"
              #js {:parseAs "string" :bodyLimit 8192}
              (fn [_request body done]
                (let [form (js/URLSearchParams. body)]
                  (if (and (= 1 (.-length (.getAll form "csrf")))
                           (= 1 (.-length (.getAll form "decision"))))
                    (done nil #js {:csrf (.get form "csrf") :decision (.get form "decision")})
                    (let [error (js/Error. "Invalid interaction form")]
                      (set! (.-statusCode error) 400)
                      (done error))))))
            (.get routes "/oidc/interaction/:uid"
              (fn ^:async show-interaction [request reply]
                (try
                  (let [details (await (.interactionDetails provider (.-raw request) (.-raw reply)))
                        uid (.. request -params -uid)
                        mailbox? (mailbox-interaction? config details)
                        owner-consent (when (and mailbox? (law/mailbox-actor-scope? (.. details -params -scope)))
                                        delegation)]
                    (if-not (= uid (.-uid details))
                      (fail! reply 400)
                      (if-let [root (await (interaction-root! pool request session-cookie-name resolve-session!
                                              mailbox? mailbox-actor-did owner-consent))]
                        ;; The root actor may have changed since the provider's
                        ;; browser session. Render current identity; the protected
                        ;; POST restarts login before it can ever grant consent.
                        (let [seed (.toString (.randomBytes crypto 32) "base64url")]
                            (.setCookie reply csrf-cookie seed
                              #js {:path "/" :httpOnly true :secure secure? :sameSite "strict" :maxAge 300})
                            (html! reply uid (.. details -params -client_id)
                                   (or (get-in root [:actor :email]) (get-in root [:actor :display_name])
                                       (get-in root [:actor :id]))
                                   (csrf-value csrf-key seed uid csrf-actor) mailbox?
                                   (when (and mailbox? actor-messaging?
                                              (law/mailbox-actor-scope? (.. details -params -scope)))
                                     mailbox-bound-actor-did)
                                   (some? owner-consent)
                                   (and owner-consent inbox-enabled?
                                        (law/mailbox-inbox-scope? (.. details -params -scope)))
                                   (when mailbox? (mailbox-redirect-source config details))))
                        (if (and mailbox? (await (current-root request session-cookie-name resolve-session!)))
                          (do (.header reply "Cache-Control" "no-store")
                              (.type reply "text/plain")
                              (.send (.code reply 403)
                                (if owner-consent
                                  "Sign in with the Axxium account that manages this Work actor."
                                  "Sign out of Axxium and sign in with the selected AT Protocol account.")))
                          (.redirect reply (str "/portal/index.html?oidc_return="
                                                (js/encodeURIComponent (str "/oidc/interaction/" uid))))))))
                  (catch :default error (fail! reply (if (<= 400 (or (.-statusCode error) 503) 499) 400 503))))))
            (.post routes "/oidc/interaction/:uid"
              (fn ^:async finish-interaction [request reply]
                (try
                  (let [uid (.. request -params -uid)]
                    (if-not (csrf-valid? request origin csrf-cookie csrf-key uid csrf-actor)
                      (fail! reply 403)
                      (let [details (await (.interactionDetails provider (.-raw request) (.-raw reply)))
                            mailbox? (mailbox-interaction? config details)
                            owner-consent (when (and mailbox? (law/mailbox-actor-scope? (.. details -params -scope)))
                                            delegation)]
                        (if-let [root (await (interaction-root! pool request session-cookie-name resolve-session!
                                               mailbox? mailbox-actor-did owner-consent))]
                          (let [actor-id (get-in root [:actor :id])
                              prompt (.. details -prompt -name)
                              scopes (str/split (or (.. details -params -scope) "") #"\s+")
                              claims (vec (array-seq (or (some-> details (aget "prompt") (aget "details") (aget "missingOIDCClaims")) #js [])))
                              admission (law/interaction-admission
                                          {:expected-uid uid :actual-uid (.-uid details)
                                           :actor-id actor-id :session-actor-id (some-> details (aget "session") (aget "accountId"))
                                           :prompt prompt :decision (.. request -body -decision)
                                           :mailbox-client? mailbox?
                                           :mailbox-actor-messaging? actor-messaging?
                                           :mailbox-owner-consent? (some? owner-consent)
                                           :mailbox-inbox? inbox-enabled?
                                           :scopes scopes :claims claims})]
                            (case (if (and mailbox? (not (mailbox-resource? config details)))
                                    :invalid-resource (:decision admission))
                            :deny
                            (do (.hijack reply)
                                (await (.interactionFinished provider (.-raw request) (.-raw reply)
                                         #js {:error "access_denied" :error_description "User declined sign-in"}
                                         #js {:mergeWithLastSubmission false})))
                            (:login :restart-login)
                            (do (.hijack reply)
                                (await (.interactionFinished provider (.-raw request) (.-raw reply)
                                         #js {:login #js {:accountId actor-id}}
                                         #js {:mergeWithLastSubmission false})))
                            :consent
                            (let [grant (new (.-Grant provider)
                                             #js {:accountId actor-id :clientId (.. details -params -client_id)})
                                  resource-scope? (set resource-scopes)]
                              (.addOIDCScope grant (str/join " " (remove resource-scope? scopes)))
                              (.addOIDCClaims grant (clj->js claims))
                              (when mailbox?
                                (.addResourceScope grant mailbox-resource
                                                   (str/join " " (filter resource-scope? scopes))))
                              (let [grant-id (await (.save grant))]
                                (await (bind-interaction-grant! pool config root grant-id actor-id
                                         (when (some #{law/mailbox-actors-scope} scopes) mailbox-bound-actor-did)
                                         owner-consent))
                                (.hijack reply)
                                (await (.interactionFinished provider (.-raw request) (.-raw reply)
                                         #js {:consent #js {:grantId grant-id}}
                                         #js {:mergeWithLastSubmission false}))))
                            :invalid-resource (fail! reply 403)
                            (fail! reply (:status admission))))
                          (fail! reply 401)))))
                  (catch :default error
                    (if (.-sent reply)
                      (do (when-not (.. reply -raw -headersSent)
                            (set! (.. reply -raw -statusCode) 503)
                            (.setHeader (.-raw reply) "Cache-Control" "no-store"))
                          (.end (.-raw reply) "Shared login could not be completed"))
                      (fail! reply (if (<= 400 (or (.-statusCode error) 503) 499) 400 503)))))))))
        (if mailbox-resource
          {:resource mailbox-resource :issuer issuer :scope law/mailbox-scope
           :scopes resource-scopes :actor-messaging? actor-messaging? :inbox? inbox-enabled?
           :access-proof! (fn [bearer] (engine/mailbox-access-proof! provider pool config mailbox-actor-did
                                         mailbox-bound-actor-did bearer delegation))
           :active-grant?
           (fn ^:async active-grant? [proof]
             (= proof (await (engine/current-mailbox-grant! pool config mailbox-actor-did
                               (:oauth/grant-id proof) (:actor-id proof)
                               (when (:mailbox/actors? proof) mailbox-bound-actor-did) delegation
                               (true? (:mailbox/inbox? proof))))))}
          true)))))
