(ns axxium.extern.oidc-provider
  "OIDC engine boundary: registered clients, durable storage and live actors."
  (:require [axxium.extern.oidc-store :as store]
            [axxium.law.oidc :as law]
            [clojure.string :as str]
            ["node:async_hooks" :refer [AsyncLocalStorage]]
            ["oidc-provider" :default Provider :refer [interactionPolicy]]))

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
  "Always require a fresh root-session consent submission for a device request,
   even when this browser already has a provider session and a reusable grant."
  []
  (let [policy (.base interactionPolicy)]
    (.add (.-checks (.get policy "consent"))
      (new (.-Check interactionPolicy)
        "device_root_approval" "Device sign-in requires explicit approval"
        (fn [ctx]
          (and (some? (.. ctx -oidc -deviceCode))
               (nil? (some-> ctx (aget "oidc") (aget "result") (aget "consent")))))))
    policy))

(defn create-provider!
  "Construct a code+PKCE authority with opt-in durable device approval.
   The HTTP mount owns browser root-session/Origin checks; polling requires only
   the registered confidential client's protocol authentication.
   lookup-actor! returns a shaped current actor or nil; inactive actors refuse."
  [pool config lookup-actor! verify-grant!]
  (law/require-config! config)
  (when-not (and (fn? lookup-actor!) (fn? verify-grant!))
    (throw (ex-info "OIDC requires current actor and parent session verification" {})))
  (let [{:keys [issuer clients cookie-keys jwks allow-loopback?]} config
        _ (require-uri! issuer allow-loopback?)
        _ (doseq [client clients uri (:redirect-uris client)]
            (require-uri! uri allow-loopback?))
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
              :clients (clj->js (mapv (fn [{:keys [id secret redirect-uris]}]
                                       {:client_id id :client_secret secret
                                        :redirect_uris redirect-uris
                                        :response_types ["code"]
                                        :grant_types (cond-> ["authorization_code"]
                                                       (:device-flow-enabled? config)
                                                       (conj "urn:ietf:params:oauth:grant-type:device_code"))
                                        :token_endpoint_auth_method "client_secret_post"}) clients))
              :jwks (clj->js jwks)
              :cookies #js {:keys (clj->js cookie-keys)}
              :features #js {:devInteractions #js {:enabled false}
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
                                                   (and (= (.-clientId token) (.-clientId client))
                                                        (some? (.-grantId token))
                                                        (await (verify-grant! (.-grantId token) (.-accountId token)))
                                                        (when-let [actor (await (lookup-actor! (.-accountId token)))]
                                                          (and (= (.-accountId token) (:id actor))
                                                               (= "active" (:status actor))))))}
                             :revocation #js {:enabled true
                                              :allowedPolicy (fn [_ctx client token]
                                                               (= (.-clientId token) (.-clientId client)))}}
              :scopes #js ["openid" "profile"]
              :claims #js {:openid #js ["sub"] :profile #js ["name"]}
              :renderError (fn [ctx _out _error]
                             (set! (.-type ctx) "text/plain")
                             (set! (.-body ctx) "Shared login request could not be completed"))
              :pkce #js {:required (fn [_ctx _client] true)}
              :ttl #js {:AccessToken 300 :AuthorizationCode 60 :Grant 86400
                        :IdToken 300 :Interaction 300 :Session 86400
                        :RefreshToken 300 :DeviceCode 600 :ClientCredentials 300
                        :BackchannelAuthenticationRequest 300}
              :interactions #js {:policy (approval-policy)
                                 :url (fn [_ctx interaction]
                                       (str "/oidc/interaction/" (.-uid interaction)))}
              :loadExistingGrant
              (fn ^:async load-grant [ctx]
                (let [oidc (.-oidc ctx)
                      session (.-session oidc)
                      grant-id (or (some-> oidc (aget "result") (aget "consent") (aget "grantId"))
                                   (.grantIdFor session (.. oidc -client -clientId)))]
                  (when (and grant-id (await (verify-grant! grant-id (.-accountId session))))
                    (await (.find (.. oidc -provider -Grant) grant-id)))))
              :findAccount
              (fn ^:async find-account [_ctx subject token]
                ;; oidc-provider's findAccount contract requires undefined for
                ;; refusal; CLJS nil is JavaScript null and becomes server_error.
                (or (when-let [actor (await (lookup-actor! subject))]
                      (when (and (= subject (:id actor)) (= "active" (:status actor))
                                 (or (nil? token)
                                     (when-let [grant-id (.-grantId token)]
                                       (await (verify-grant! grant-id subject)))))
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
              (await (next))
              (when (and (= "device_authorization" (.. ctx -oidc -route))
                         (= 200 (.-status ctx))
                         (some? (some-> ctx (aget "body") (aget "device_code"))))
                (aset (.-body ctx) "interval" 5))))))
      provider)))
