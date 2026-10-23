(ns axxium.extern.oidc-provider
  "OIDC engine boundary: registered clients, durable storage and live actors."
  (:require [axxium.extern.oidc-store :as store]
            [axxium.law.oidc :as law]
            ["oidc-provider" :default Provider]))

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

(defn create-provider!
  "Construct a code+PKCE authority. Device issuance stays disabled until its
   durable polling and explicit approval adapters are wired and verified.
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
    (new Provider issuer
         #js {:adapter (store/adapter-factory pool)
              :clients (clj->js (mapv (fn [{:keys [id secret redirect-uris]}]
                                       {:client_id id :client_secret secret
                                        :redirect_uris redirect-uris
                                        :response_types ["code"]
                                        :grant_types ["authorization_code"]
                                        :token_endpoint_auth_method "client_secret_post"}) clients))
              :jwks (clj->js jwks)
              :cookies #js {:keys (clj->js cookie-keys)}
              :features #js {:devInteractions #js {:enabled false}
                             :deviceFlow #js {:enabled false}
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
              :interactions #js {:url (fn [_ctx interaction]
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
                (when-let [actor (await (lookup-actor! subject))]
                  (when (and (= subject (:id actor)) (= "active" (:status actor))
                             (or (nil? token)
                                 (when-let [grant-id (.-grantId token)]
                                   (await (verify-grant! grant-id subject)))))
                    #js {:accountId subject
                         :claims (fn ^:async account-claims [_use _scope _claims _rejected]
                                   #js {:sub subject :name (:display_name actor)})})))})))
