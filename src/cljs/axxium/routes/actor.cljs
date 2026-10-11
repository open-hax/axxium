(ns axxium.routes.actor
  "Self-service identity reads; account registration does not confer registry administration."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.domain.identity :as identity]
            [axxium.extern.http :as http]))
(defn- ^:async own-actor! [request]
  (let [actor (await (session/require-actor! request)) requested (get-in request [:params :id])]
    (when (and requested (not= requested (:id actor)))
      (throw (ex-info "This account cannot read another identity" {:status 403})))
    {:body {:ok true :actor (identity/public-actor actor)}}))
(defn register-actor-routes! "Expose only the current account and its entity record." [app]
  (http/route! app "GET" "/api/actors/me" own-actor! cfg/config)
  (http/route! app "GET" "/api/actors/:id" own-actor! cfg/config)
  (http/route! app "GET" "/api/entities/:id"
    (^:async fn [request]
      (let [actor (await (session/require-actor! request)) id (get-in request [:params :id])]
        (when-not (= id (:entity_id actor))
          (throw (ex-info "This account cannot read another entity" {:status 403})))
        {:body {:ok true :entity (await (db/query-one-sql (db/q-select-entity-by-id id)))}})) cfg/config))
