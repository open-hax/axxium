(ns axxium.extern.jose
  "Thin extern wrapper around the `jose` npm module."
  (:require ["jose" :as jose-lib]
            [axxium.law.google-state :as google-state-law]))

(def SignJWT (.-SignJWT jose-lib))
(def jwtVerify (.-jwtVerify jose-lib))

(defn sign-actor! [claims {:keys [secret issuer audience expiry-hours]}]
  (let [key (.encode (new (.-TextEncoder js/globalThis)) secret)]
    (-> (new SignJWT (clj->js claims))
        (.setProtectedHeader #js {"alg" "HS256" "typ" "JWT"})
        (.setIssuedAt)
        (.setIssuer issuer)
        (.setAudience audience)
        (.setExpirationTime (str expiry-hours "h"))
        (.sign key))))

(defn verify-actor! [token {:keys [secret issuer audience]}]
  (let [key (.encode (new (.-TextEncoder js/globalThis)) secret)]
    (-> (jwtVerify token key #js {:issuer issuer :audience audience
                                  :clockTolerance 60})
        (.then (fn [result]
                 (js->clj (.-payload result) :keywordize-keys true))))))

(def ^:private google-state-audience "axxium-google-oauth-state")

(defn- google-state-claims
  "Decode the three Google-state claims from a verified JWT payload."
  [payload]
  {:state (.-state payload) :nonce (.-nonce payload) :verifier (.-verifier payload)})

(defn ^:async sign-google-state!
  "Seal Google OAuth state, nonce and PKCE verifier into a 5-minute HS256 token.
  Throws when the claims fail `axxium.law.google-state/GoogleState`."
  [claims {:keys [secret issuer]}]
  (when-not (seq secret)
    (throw (ex-info "Google OAuth state secret is missing" {})))
  (let [{:keys [state nonce verifier] :as admitted} (select-keys claims [:state :nonce :verifier])]
    (when-not (google-state-law/valid-google-state? admitted)
      (throw (ex-info "Google OAuth state is outside its contract" {:contract :axxium.law.google-state/GoogleState})))
    (let [key (.encode (new (.-TextEncoder js/globalThis)) secret)]
      (-> (new SignJWT #js {:state state :nonce nonce :verifier verifier})
          (.setProtectedHeader #js {"alg" "HS256" "typ" "JWT"})
          (.setIssuedAt)
          (.setIssuer issuer)
          (.setAudience google-state-audience)
          (.setExpirationTime "5m")
          (.sign key)
          (await)))))

(defn ^:async verify-google-state!
  "Return {:state :nonce :verifier} for a valid sealed state token whose claims
  satisfy `axxium.law.google-state/GoogleState`, or nil."
  [token {:keys [secret issuer]}]
  (when (seq secret)
    (let [key (.encode (new (.-TextEncoder js/globalThis)) secret)]
      (try
        (let [result (await (jwtVerify (str token) key #js {:issuer issuer :audience google-state-audience
                                                            :algorithms #js ["HS256"]
                                                            :requiredClaims #js ["exp"]}))
              claims (google-state-claims (.-payload result))]
          (when (google-state-law/valid-google-state? claims)
            claims))
        (catch :default _
          nil)))))
