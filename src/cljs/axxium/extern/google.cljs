(ns axxium.extern.google
  "Google OIDC boundary. The client secret is read from an operator-owned file."
  (:require [axxium.config :as cfg]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["jose" :as jose]))

(def ^:private authorization-endpoint
  "https://accounts.google.com/o/oauth2/v2/auth")
(def ^:private token-endpoint
  "https://oauth2.googleapis.com/token")
(def ^:private google-keys
  (jose/createRemoteJWKSet (js/URL. "https://www.googleapis.com/oauth2/v3/certs")))

(defn- client []
  (let [file (cfg/get-in-config [:oauth/google-client-file])]
    (when (seq file)
      (let [document (js/JSON.parse (.readFileSync fs file "utf8"))]
        (.-web document)))))

(defn- callback-uri []
  (str (cfg/get-in-config [:axxium/public-base-url]) "/api/auth/google/callback"))

(defn configured? []
  (try
    (let [web (client)]
      (and web
           (some #{(callback-uri)} (js->clj (.-redirect_uris web)))
           (seq (.-client_id web))
           (seq (.-client_secret web))))
    (catch :default _ false)))

(defn random-secret []
  (.toString (.randomBytes crypto 32) "base64url"))

(defn authorize-url [state nonce verifier]
  (let [web (client)
        url (js/URL. authorization-endpoint)
        params (.-searchParams url)
        challenge (-> (.createHash crypto "sha256")
                      (.update verifier)
                      (.digest "base64url"))]
    (.set params "client_id" (.-client_id web))
    (.set params "redirect_uri" (callback-uri))
    (.set params "response_type" "code")
    (.set params "scope" "openid email profile")
    (.set params "state" state)
    (.set params "nonce" nonce)
    (.set params "code_challenge" challenge)
    (.set params "code_challenge_method" "S256")
    (.toString url)))

(defn ^:async verified-profile! [code verifier nonce]
  (let [web (client)
        body (js/URLSearchParams.)]
    (.set body "grant_type" "authorization_code")
    (.set body "code" code)
    (.set body "client_id" (.-client_id web))
    (.set body "client_secret" (.-client_secret web))
    (.set body "redirect_uri" (callback-uri))
    (.set body "code_verifier" verifier)
    (let [response (await (js/fetch token-endpoint
                                    #js {:method "POST" :body body
                                         :redirect "error"}))]
      (when-not (.-ok response)
        (let [failure (try (await (.json response))
                           (catch :default _ nil))]
          (throw (ex-info "Google token exchange failed"
                          {:stage :token-exchange
                           :provider-status (.-status response)
                           :provider-error (some-> failure .-error)}))))
      (let [tokens (await (.json response))
            id-token (.-id_token tokens)
            verified (try
                       (await (jose/jwtVerify
                               id-token google-keys
                               #js {:audience (.-client_id web)
                                    :issuer #js ["https://accounts.google.com"
                                                 "accounts.google.com"]}))
                       (catch :default error
                         (throw (ex-info "Google ID token verification failed"
                                         {:stage :id-token
                                          :reason (.-code error)}))))
            payload (.-payload verified)]
        (when-not (and (= nonce (.-nonce payload))
                       (true? (.-email_verified payload))
                       (seq (.-sub payload))
                       (seq (.-email payload)))
          (throw (ex-info "Google identity claim rejected" {:stage :claims})))
        {:subject (.-sub payload)
         :email (.-email payload)
         :display-name (or (.-name payload) (.-email payload))}))))
