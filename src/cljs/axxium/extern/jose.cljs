(ns axxium.extern.jose
  "Named JOSE adapters; callers exchange Clojure data and opaque key handles."
  (:require ["jose" :as jose]))

(defn- secret-bytes [secret] (.encode (js/TextEncoder.) secret))
(defn ^:async sign-session "Sign a local session JWT." [claims secret issuer audience hours]
  (await (-> (jose/SignJWT. (clj->js claims))
             (.setProtectedHeader #js {:alg "HS256" :typ "JWT"})
             (.setIssuedAt) (.setIssuer issuer) (.setAudience audience)
             (.setExpirationTime (str hours "h")) (.sign (secret-bytes secret)))))
(defn ^:async verify-session "Validate the local algorithm, issuer, audience and expiry." [token secret issuer audience]
  (let [result (await (jose/jwtVerify token (secret-bytes secret)
                                     #js {:algorithms #js ["HS256"] :issuer issuer :audience audience}))]
    (js->clj (.-payload result) :keywordize-keys true)))
(defn ^:async sign-transfer "Sign a short-lived recipient-bound identity transfer." [claims private-jwk issuer audience]
  (let [key (await (jose/importJWK (clj->js private-jwk) "EdDSA"))]
    (await (-> (jose/SignJWT. (clj->js claims))
               (.setProtectedHeader #js {:alg "EdDSA" :typ "axxium-transfer+jwt"})
               (.setIssuedAt) (.setIssuer issuer) (.setAudience audience)
               (.setExpirationTime "5m") (.sign key)))))
(defn unverified-issuer "Read issuer only to select a pre-pinned key; never trust other unverified claims." [token]
  (.-iss (jose/decodeJwt token)))
(defn ^:async verify-transfer "Verify a pinned issuer key and exact local audience." [token public-jwk issuer audience]
  (let [key (await (jose/importJWK (clj->js public-jwk) "EdDSA"))
        result (await (jose/jwtVerify token key
                         #js {:algorithms #js ["EdDSA"] :typ "axxium-transfer+jwt"
                              :issuer issuer :audience audience :maxTokenAge "5m"}))]
    (js->clj (.-payload result) :keywordize-keys true)))
