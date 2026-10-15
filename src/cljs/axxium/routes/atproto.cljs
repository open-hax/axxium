(ns axxium.routes.atproto
  "Read-only, bidirectionally verified AT Protocol identity lookup."
  (:require [axxium.extern.atproto :as atproto]
            [axxium.extern.http :as http]
            [axxium.law.atproto :as atproto-law]
            [axxium.routes.auth-context :as auth]))

(defn- ^:async resolve!
  "Resolve an identity for an admitted administrator (401 anonymous, 403 non-admin)."
  [req reply _context]
  (let [identity (http/query-param req "identity")]
    (cond
      (not (atproto-law/identity? identity))
      (http/send! reply 400 {:error "A valid DID or handle is required"})

      :else
      (try
        (if-let [result (await (atproto/resolve-identity!
                               identity (atproto-law/did? identity)))]
          (http/send! reply 200 {:identity result})
          (http/send! reply 404 {:error "AT Protocol identity not verified"}))
        (catch :default _
          (http/send! reply 502 {:error "AT Protocol identity resolution failed"}))))))

(defn register-atproto-routes! [app]
  (http/get! app "/api/atproto/resolve" (auth/with-admin resolve!)))
