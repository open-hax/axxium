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
(def ^:private drive-actor-state-audience "axxium-drive-actor-oauth-state")

(defn- sign-state!
  [claims audience {:keys [private-key kid issuer]}]
  (-> (new SignJWT claims)
      (.setProtectedHeader #js {"alg" "EdDSA" "typ" "JWT" "kid" kid})
      (.setIssuedAt)
      (.setIssuer issuer)
      (.setAudience audience)
      (.setExpirationTime "5m")
      (.sign private-key)))

(defn- ^:async verify-state!
  [token audience {:keys [public-key kid issuer]}]
  (when (and (key-handle? public-key) (signing-law/kid? kid))
    (try
      (let [result (await (jwtVerify (str token) public-key
                                    #js {:issuer issuer :audience audience
                                         :algorithms #js ["EdDSA"]
                                         :requiredClaims #js ["exp"]}))]
        (when (signing-law/kid-admitted? (header-kid result) kid)
          (.-payload result)))
      (catch :default _ nil))))

(defn- google-state-claims
  "Decode the three Google-state claims from a verified JWT payload."
  [payload]
  {:state (.-state payload) :nonce (.-nonce payload) :verifier (.-verifier payload)})

(defn ^:async sign-google-state!
  "Seal Google OAuth state, nonce and PKCE verifier into a 5-minute EdDSA token.
  Throws when the signing key is missing or the claims fail
  `axxium.law.google-state/GoogleState`."
  [claims {:keys [private-key kid] :as keys}]
  (require-signing-key! private-key kid)
  (let [{:keys [state nonce verifier] :as admitted} (select-keys claims [:state :nonce :verifier])]
    (when-not (google-state-law/valid-google-state? admitted)
      (throw (ex-info "Google OAuth state is outside its contract" {:contract :axxium.law.google-state/GoogleState})))
    (await (sign-state! #js {:state state :nonce nonce :verifier verifier}
                        google-state-audience keys))))

(defn ^:async verify-google-state!
  "Return {:state :nonce :verifier} for a valid sealed state token whose kid
  is this instance's and whose claims satisfy `axxium.law.google-state/GoogleState`,
  or nil."
  [token keys]
  (when-let [payload (await (verify-state! token google-state-audience keys))]
    (let [claims (google-state-claims payload)]
      (when (google-state-law/valid-google-state? claims) claims))))

(defn sign-drive-actor-state!
  "Sign the selected actor and exact operator session for one Drive OAuth flow."
  [claims {:keys [private-key kid] :as keys}]
  (require-signing-key! private-key kid)
  (when-not (google-state-law/valid-drive-actor-state? claims)
    (throw (ex-info "Drive enrollment state is outside its contract"
                    {:contract :axxium.law.google-state/DriveActorState})))
  (sign-state! (clj->js claims) drive-actor-state-audience keys))

(defn ^:async verify-drive-actor-state!
  "Read verified actor enrollment state without accepting other OAuth states."
  [token keys]
  (when-let [payload (await (verify-state! token drive-actor-state-audience keys))]
    (let [claims (select-keys (js->clj payload :keywordize-keys true)
                             [:state :verifier :actor-did :root-did :session-id])]
      (when (google-state-law/valid-drive-actor-state? claims) claims))))
