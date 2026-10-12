(ns axxium.extern.jose
  "Thin extern wrapper around the `jose` npm module."
  (:require ["jose" :as jose-lib]))

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

(defn sign-google-state!
  "Seal Google OAuth state, nonce and PKCE verifier into a 5-minute HS256 token."
  [{:keys [state nonce verifier]} {:keys [secret issuer]}]
  (let [key (.encode (new (.-TextEncoder js/globalThis)) secret)]
    (-> (new SignJWT #js {:state state :nonce nonce :verifier verifier})
        (.setProtectedHeader #js {"alg" "HS256" "typ" "JWT"})
        (.setIssuedAt)
        (.setIssuer issuer)
        (.setAudience google-state-audience)
        (.setExpirationTime "5m")
        (.sign key))))

(defn verify-google-state!
  "Return {:state :nonce :verifier} for a valid sealed state token, or nil."
  [token {:keys [secret issuer]}]
  (let [key (.encode (new (.-TextEncoder js/globalThis)) secret)]
    (-> (jwtVerify (str token) key #js {:issuer issuer :audience google-state-audience
                                        :algorithms #js ["HS256"]})
        (.then (fn [result]
                 (let [p (.-payload result)]
                   {:state (.-state p) :nonce (.-nonce p) :verifier (.-verifier p)})))
        (.catch (fn [_] nil)))))
