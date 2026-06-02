(ns axxium.extern.jose
  "Thin extern wrapper around the `jose` npm module."
  (:require ["jose" :as jose-lib]))

(def SignJWT (.-SignJWT jose-lib))
(def jwtVerify (.-jwtVerify jose-lib))
