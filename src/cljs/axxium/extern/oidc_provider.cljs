(ns axxium.extern.oidc-provider
  "OIDC engine boundary: registered clients, durable storage and live actors."
  (:require [axxium.extern.oidc-store :as store]
            [axxium.law.oidc :as law]
            [clojure.string :as str]
            ["node:async_hooks" :refer [AsyncLocalStorage]]
            ["oidc-provider" :default Provider :refer [errors interactionPolicy]]))

(defn- require-uri!
  "Accept exact HTTPS URLs; explicit loopback fixtures may use local HTTP."
  [value allow-loopback?]
  (let [url (js/URL. value)
        local? (contains? #{"127.0.0.1" "[::1]" "localhost"} (.-hostname url))]
    (when-not (and (or (= "https:" (.-protocol url))
                       (and allow-loopback? local? (= "http:" (.-protocol url))))
                   (= "" (.-username url)) (= "" (.-password url))
                   (= "" (.-hash url)) (= "" (.-search url)))
      (throw (ex-info "OIDC URI must be an exact HTTPS endpoint" {}))))
  value)

(defn- resource-indicators
  "Confine resource grants to the configured mailbox client and audience."
  [{:keys [mailbox-resource] :as config}]
  #js {:getResourceServerInfo
       (fn [_ctx resource client]
         (if (and (law/mailbox-client? config (.-clientId client)) (= mailbox-resource resource))
           #js {:scope (str/join " " (law/mailbox-resource-scopes config))
                :audience mailbox-resource :accessTokenFormat "opaque"}
           (throw (new (.-InvalidTarget errors)))))
       :defaultResource
       (fn [_ctx client requested]
         (if (law/mailbox-client? config (.-clientId client))
           (if (and (array? requested) (= 1 (alength requested)) (= mailbox-resource (aget requested 0)))
             mailbox-resource
             (throw (new (.-InvalidTarget errors))))
           (or requested js/undefined)))
       :useGrantedResource
       (fn [ctx source]
         (and (law/mailbox-client? config (.. ctx -oidc -client -clientId))
              (.has (.-resourceIndicators source) mailbox-resource)))})

(defn ^:async current-mailbox-grant!
  "Require the grant to belong to the currently selected mailbox recipient.
   Actor messaging also requires the seeded actor DID bound at consent."
  ([pool config owner grant-id account-id]
   (await (current-mailbox-grant! pool config owner grant-id account-id nil)))
  ([pool {:keys [mailbox-client-id mailbox-resource mailbox-actor-messaging?]} owner grant-id account-id bound-actor-did]
   (when (or (nil? bound-actor-did)
             (and (true? mailbox-actor-messaging?) (law/mailbox-bound-actor-did? bound-actor-did)))
     (when-let [proof (await (store/mailbox-grant-proof! pool grant-id account-id
                                                       mailbox-client-id mailbox-resource bound-actor-did))]
       (when (= owner (get-in proof [:principal :auth/did]))
         (cond-> proof bound-actor-did (assoc :mailbox/actors? true)))))))

(defn- current-grant!
  "Apply the mailbox's verified-AT parent law to its dedicated client only."
  [pool config owner verify-grant! grant-id account-id client-id]
  (if (law/mailbox-client? config client-id)
    (current-mailbox-grant! pool config owner grant-id account-id)
    (verify-grant! grant-id account-id)))

(defn ^:async mailbox-access-proof!
  "Resolve one opaque resource access token to its current mailbox grant proof."
  [provider pool {:keys [issuer mailbox-client-id] :as config} owner bound-actor-did bearer]
  (when (and mailbox-client-id provider (= issuer (.-issuer provider))
             (string? bearer) (re-matches #"[A-Za-z0-9_-]{1,512}" bearer))
    (when-let [token (await (.find (.-AccessToken provider) bearer))]
      (let [account-id (.-accountId token)
            grant-id (.-grantId token)
            scope (.-scope token)
            actor-scope? (law/mailbox-actor-scope? scope)]
        (when (and (true? (.-isValid token))
                   (not (.isSenderConstrained token))
                   (or (not actor-scope?) (law/mailbox-bound-actor-did? bound-actor-did))
                   (law/mailbox-access-claims?
                    config {:client-id (.-clientId token)
                            :token-issuer (some-> token (aget "extra") (aget "iss"))
                            :audience (.-aud token) :scope scope
                            :account-id account-id :grant-id grant-id}))
          (await (current-mailbox-grant! pool config owner grant-id account-id
                                         (when actor-scope? bound-actor-did))))))))

(defn- html-escape
  "Encode labels supplied by registered clients and displayed user codes."
  [value]
  (str/escape (str value) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn- device-page!
  "Render the engine-owned XSRF form without external content or caching."
  [ctx title content]
  (.set ctx "Cache-Control" "no-store")
  (.set ctx "Referrer-Policy" "no-referrer")
  (.set ctx "Content-Security-Policy" "default-src 'none'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
  (set! (.-type ctx) "text/html")
  (set! (.-body ctx) (str "<!doctype html><html><head><meta charset=utf-8><title>Axxium device approval</title></head><body><h1>"
                         title "</h1>" content "</body></html>")))

(defn- approval-policy
  "Require fresh root-session consent for device and mailbox authorization."
  [config]
  (let [policy (.base interactionPolicy)
        checks (.-checks (.get policy "consent"))]
    (.add checks
      (new (.-Check interactionPolicy)
        "device_root_approval" "Device sign-in requires explicit approval"
        (fn [ctx]
          (and (some? (.. ctx -oidc -deviceCode))
               (nil? (some-> ctx (aget "oidc") (aget "result") (aget "consent")))))))
    (.add checks
      (new (.-Check interactionPolicy)
        "mailbox_root_approval" "Mailbox authorization requires explicit approval"
        (fn [ctx]
          (and (law/mailbox-client? config (some-> ctx (aget "oidc") (aget "client") (aget "clientId")))
               (nil? (some-> ctx (aget "oidc") (aget "result") (aget "consent")))))))
    policy))

(defn- bounded-ttl
  "Use the pure parent-bound issuance contract after findAccount/issueRefreshToken."
  [maximum-seconds]
  (fn [ctx _token _client]
    (or (law/bounded-token-lifetime (aget ctx "axxiumRootExpiry") (.now js/Date) maximum-seconds)
        (throw (new (.-InvalidGrant errors) "Root session expired")))))

(defn create-provider!
  "Construct a code+PKCE authority with opt-in durable device approval.
   The HTTP mount owns browser root-session/Origin checks; polling requires only
   the registered confidential client's protocol authentication.
   lookup-actor! returns a shaped current actor or nil; inactive actors refuse."
  [pool config lookup-actor! verify-grant! mailbox-owner]
  (law/require-config! config)
  (when-not (and (fn? lookup-actor!) (fn? verify-grant!))
    (throw (ex-info "OIDC requires current actor and parent session verification" {})))
  (let [{:keys [issuer clients cookie-keys jwks allow-loopback? mailbox-resource]} config
        _ (require-uri! issuer allow-loopback?)
        _ (doseq [client clients uri (:redirect-uris client)]
            (require-uri! uri allow-loopback?))
        _ (when mailbox-resource
            (require-uri! mailbox-resource allow-loopback?)
            (when-not (= mailbox-resource (str (.-origin (js/URL. issuer)) "/api/mailbox/mcp"))
              (throw (ex-info "OIDC mailbox resource must be the exact primary MCP endpoint" {}))))
        keys (:keys jwks)]
    (when-not (and (seq keys)
                   (every? #(and (= "RSA" (:kty %)) (= "RS256" (:alg %))
                                 (string? (:kid %)) (seq (:kid %))
                                 (string? (:d %)) (seq (:d %))) keys))
      (throw (ex-info "OIDC requires persistent private RS256 signing keys" {})))
    (let [context (new AsyncLocalStorage)
          provider
          (new Provider issuer
         #js {:adapter (store/adapter-factory pool
                         (fn []
                           (when-let [ctx (.getStore context)]
                             {:poll? (and (= "token" (.. ctx -oidc -route))
                                          (= "urn:ietf:params:oauth:grant-type:device_code"
                                             (some-> ctx (aget "oidc") (aget "params") (aget "grant_type"))))
                              :authenticated-client-id (some-> ctx (aget "oidc") (aget "client") (aget "clientId"))})))
              :clients (clj->js (mapv (fn [{:keys [id secret redirect-uris refresh-enabled? device-enabled?]}]
                                       {:client_id id :client_secret secret
                                        :redirect_uris redirect-uris
                                        :response_types ["code"]
                                        :grant_types (cond-> ["authorization_code"]
                                                       (and (:device-flow-enabled? config)
                                                            (not (law/mailbox-client? config id))
                                                            (not= false device-enabled?))
                                                       (conj "urn:ietf:params:oauth:grant-type:device_code")
                                                       refresh-enabled? (conj "refresh_token"))
                                        :token_endpoint_auth_method "client_secret_post"}) clients))
              :jwks (clj->js jwks)
              :cookies #js {:keys (clj->js cookie-keys)}
              :features #js {:devInteractions #js {:enabled false}
                             :resourceIndicators (resource-indicators config)
                             :deviceFlow #js {:enabled (true? (:device-flow-enabled? config))
                                              :deviceInfo (fn [_ctx] nil)
                                              :userCodeInputSource
                                              (fn [ctx form _out error]
                                                (device-page! ctx "Approve another device"
                                                  (str (if error "<p>Device request could not be completed.</p>"
                                                           "<p>Enter the code shown on the device you are signing into.</p>")
                                                       form "<button type=submit form='op.deviceInputForm'>Continue</button>")))
                                              :userCodeConfirmSource
                                              (fn [ctx form client _device-info user-code]
                                                (device-page! ctx "Check your device code"
                                                  (str "<p>Sign in to " (html-escape (.-clientId client)) " with code <strong>"
                                                       (html-escape user-code) "</strong>.</p>"
                                                       "<p>Continue only if you requested this sign-in and the codes match.</p>"
                                                       form "<button type=submit form='op.deviceConfirmForm'>Continue to approval</button>"
                                                       "<button type=submit form='op.deviceConfirmForm' name=abort value=yes>Cancel</button>")))
                                              :successSource (fn [ctx]
                                                               (device-page! ctx "Device approved"
                                                                 "<p>You can return to the other device.</p>"))}
                             :introspection #js {:enabled true
                                                 :allowedPolicy
                                                 (fn ^:async introspection-policy [_ctx client token]
                                                   (boolean
                                                     (and (= (.-clientId token) (.-clientId client))
                                                          (some? (.-grantId token))
                                                          (await (current-grant! pool config mailbox-owner verify-grant!
                                                                   (.-grantId token) (.-accountId token)
                                                                   (.-clientId client)))
                                                          (when-let [actor (await (lookup-actor! (.-accountId token)))]
                                                            (and (= (.-accountId token) (:id actor))
                                                                 (= "active" (:status actor)))))))}
                             :revocation #js {:enabled true
                                              :allowedPolicy (fn [_ctx client token]
                                                               (= (.-clientId token) (.-clientId client)))}}
              :issueRefreshToken
              (fn ^:async bounded-refresh [ctx client source]
                (if (.grantTypeAllowed client "refresh_token")
                  (do (aset ctx "axxiumRootExpiry"
                        (await (store/session-grant-expiry! pool (.-grantId source) (.-accountId source))))
                      true)
                  false))
              :rotateRefreshToken true
              :revokeGrantPolicy
              (fn [ctx]
                ;; oidc-provider 9.13.0 preserves a grant on access-token
                ;; revocation. The mailbox worker relies on that grant, so
                ;; its dedicated client's disconnect must revoke it as well.
                (or (law/mailbox-client? config (some-> ctx (aget "oidc") (aget "client") (aget "clientId")))
                    (not (and (= "revocation" (.. ctx -oidc -route))
                              (some? (.. ctx -oidc -entities -AccessToken))))))
              :extraTokenClaims (fn [_ctx token]
                                  (if (and (= "AccessToken" (.-kind token))
                                           (law/mailbox-client? config (.-clientId token))
                                           (= mailbox-resource (.-aud token)))
                                    #js {:iss issuer}
                                    js/undefined))
              :scopes #js ["openid" "profile"]
              :claims #js {:openid #js ["sub"] :profile #js ["name"]}
              :renderError (fn [ctx _out _error]
                             (set! (.-type ctx) "text/plain")
                             (set! (.-body ctx) "Shared login request could not be completed"))
              :pkce #js {:required (fn [_ctx _client] true)}
              :ttl #js {:AccessToken (bounded-ttl 300) :AuthorizationCode 60 :Grant 86400
                        :IdToken (bounded-ttl 300) :Interaction 300 :Session 86400
                        :RefreshToken (bounded-ttl 86400) :DeviceCode 600 :ClientCredentials 300
                        :BackchannelAuthenticationRequest 300}
              :interactions #js {:policy (approval-policy config)
                                 :url (fn [_ctx interaction]
                                       (str "/oidc/interaction/" (.-uid interaction)))}
              :loadExistingGrant
              (fn ^:async load-grant [ctx]
                (let [oidc (.-oidc ctx)
                      session (.-session oidc)
                      client-id (.. oidc -client -clientId)
                      grant-id (or (some-> oidc (aget "result") (aget "consent") (aget "grantId"))
                                   (.grantIdFor session client-id))]
                  (when (and grant-id (await (current-grant! pool config mailbox-owner verify-grant!
                                               grant-id (.-accountId session) client-id)))
                    (await (.find (.. oidc -provider -Grant) grant-id)))))
              :findAccount
              (fn ^:async find-account [ctx subject token]
                ;; This documented seam runs before refresh rotation or issuance.
                ;; Refusal must be undefined, rather than CLJS null.
                (or (when-let [actor (await (lookup-actor! subject))]
                      (when (and (= subject (:id actor)) (= "active" (:status actor))
                                 (or (nil? token)
                                     (when-let [grant-id (.-grantId token)]
                                       (await (current-grant! pool config mailbox-owner verify-grant!
                                                grant-id subject (.-clientId token))))))
                        (when token
                          (aset ctx "axxiumRootExpiry"
                            (await (store/session-grant-expiry! pool (.-grantId token) subject))))
                        #js {:accountId subject
                             :claims (fn ^:async account-claims [_use _scope _claims _rejected]
                                       #js {:sub subject :name (:display_name actor)})}))
                    js/undefined))})]
      ;; Public Provider.use wraps the engine pipeline. The adapter reads this
      ;; request context only at DeviceCode.find, after token clientAuth passed.
      (.use provider
        (fn [ctx next]
          (.run context ctx
            (fn ^:async protocol-context []
              (try
                (await (next))
                (finally
                  (when-let [rotated (some-> ctx (aget "oidc") (aget "entities") (aget "RotatedRefreshToken"))]
                    (when (>= (.-status ctx) 400)
                      (await (store/revoke-family! pool (.-grantId rotated)))))))
              (when (and (= "introspection" (some-> ctx (aget "oidc") (aget "route")))
                         (true? (some-> ctx (aget "body") (aget "active"))))
                (let [token (some-> ctx (aget "oidc") (aget "entities") (aget "AccessToken"))]
                  (when token
                    (aset (.-body ctx) "root_session_expires_at"
                      (js/Math.floor (/ (await (store/session-grant-expiry! pool (.-grantId token) (.-accountId token))) 1000))))))
              (when (and (= "device_authorization" (some-> ctx (aget "oidc") (aget "route")))
                         (= 200 (.-status ctx))
                         (some? (some-> ctx (aget "body") (aget "device_code"))))
                (aset (.-body ctx) "interval" 5))))))
      provider)))
