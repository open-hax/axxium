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
