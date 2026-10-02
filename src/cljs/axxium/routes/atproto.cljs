(ns axxium.routes.atproto
  "Read-only, bidirectionally verified AT Protocol identity lookup."
  (:require [axxium.auth.session :as session]
            [axxium.db :as db]
            [axxium.extern.atproto :as atproto]
            [axxium.extern.http :as http]
            [axxium.law.actor :as actor-law]
            [axxium.law.atproto :as atproto-law]))

(defn- ^:async admin? [req]
  (when-let [ctx (await (session/resolve-auth-context req))]
    (let [entity (await (db/query-one-sql
                         (db/q-select-entity-for-actor (:auth/actor-id ctx))))]
      (actor-law/system-admin? ctx entity))))

(defn- ^:async resolve! [req reply]
  (let [identity (http/query-param req "identity")]
    (cond
      (not (await (admin? req)))
      (http/send! reply 403 {:error "System administrator required"})

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
  (http/get! app "/api/atproto/resolve" resolve!))
