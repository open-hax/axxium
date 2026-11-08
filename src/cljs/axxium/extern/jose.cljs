(ns axxium.extern.jose
  "Thin extern wrapper around the `jose` npm module.
   Actor tokens and Google OAuth state are EdDSA signed by this instance's
   Ed25519 key. Verification admits only EdDSA and the instance kid."
  (:require ["jose" :as jose-lib]
            [axxium.law.google-state :as google-state-law]
            [axxium.law.signing :as signing-law]))

(def SignJWT (.-SignJWT jose-lib))
(def jwtVerify (.-jwtVerify jose-lib))

(defn- key-handle?
  "A jose KeyObject. cljs.core/object? is only true for plain Objects."
  [value]
  (instance? js/Object value))

(defn- header-kid
  [result]
  (some-> result .-protectedHeader .-kid))

(defn- require-signing-key!
  [private-key kid]
  (when-not (and (key-handle? private-key) (signing-law/kid? kid))
    (throw (ex-info "Signing key is not loaded" {:reason :not-loaded}))))

(defn- admit-kid!
  [result kid]
  (when-not (signing-law/kid-admitted? (header-kid result) kid)
    (throw (ex-info "token kid is not this instance" {:reason :kid})))
  result)

(defn sign-actor! [claims {:keys [private-key kid issuer audience expiry-hours]}]
  (require-signing-key! private-key kid)
  (-> (new SignJWT (clj->js claims))
      (.setProtectedHeader #js {"alg" "EdDSA" "typ" "JWT" "kid" kid})
      (.setIssuedAt)
      (.setIssuer issuer)
      (.setAudience audience)
      (.setExpirationTime (str expiry-hours "h"))
      (.sign private-key)))

(defn verify-actor! [token {:keys [public-key kid issuer audience]}]
  (-> (jwtVerify token public-key #js {:issuer issuer :audience audience
                                       :algorithms #js ["EdDSA"]
                                       :requiredClaims #js ["exp"]
                                       :clockTolerance 60})
      (.then (fn [result]
               (admit-kid! result kid)
               (js->clj (.-payload result) :keywordize-keys true)))))

(def ^:private google-state-audience "axxium-google-oauth-state")

(defn- google-state-claims
  "Decode the three Google-state claims from a verified JWT payload."
  [payload]
  {:state (.-state payload) :nonce (.-nonce payload) :verifier (.-verifier payload)})

(defn ^:async sign-google-state!
  "Seal Google OAuth state, nonce and PKCE verifier into a 5-minute EdDSA token.
  Throws when the signing key is missing or the claims fail
  `axxium.law.google-state/GoogleState`."
  [claims {:keys [private-key kid issuer]}]
  (require-signing-key! private-key kid)
  (let [{:keys [state nonce verifier] :as admitted} (select-keys claims [:state :nonce :verifier])]
    (when-not (google-state-law/valid-google-state? admitted)
      (throw (ex-info "Google OAuth state is outside its contract" {:contract :axxium.law.google-state/GoogleState})))
    (-> (new SignJWT #js {:state state :nonce nonce :verifier verifier})
        (.setProtectedHeader #js {"alg" "EdDSA" "typ" "JWT" "kid" kid})
        (.setIssuedAt)
        (.setIssuer issuer)
        (.setAudience google-state-audience)
        (.setExpirationTime "5m")
        (.sign private-key)
        (await))))

(defn ^:async verify-google-state!
  "Return {:state :nonce :verifier} for a valid sealed state token whose kid
  is this instance's and whose claims satisfy `axxium.law.google-state/GoogleState`,
  or nil."
  [token {:keys [public-key kid issuer]}]
  (when (and (key-handle? public-key) (signing-law/kid? kid))
    (try
      (let [result (await (jwtVerify (str token) public-key
                                     #js {:issuer issuer :audience google-state-audience
                                          :algorithms #js ["EdDSA"]
                                          :requiredClaims #js ["exp"]}))
            claims (google-state-claims (.-payload result))]
        (when (and (signing-law/kid-admitted? (header-kid result) kid)
                   (google-state-law/valid-google-state? claims))
          claims))
      (catch :default _
        nil))))
