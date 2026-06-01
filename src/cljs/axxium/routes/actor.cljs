(ns axxium.routes.actor
  "Actor registry routes for Axxium."
  (:require [axxium.db :as db]
            [axxium.auth.session :as session]))

(defn- sanitize-actor [actor]
  (dissoc actor :password_hash))

(defn register-actor-routes! [app]
  (.get app "/api/actors"
    (fn [req reply]
      (-> (session/resolve-auth-context req)
          (.then
            (fn [ctx]
              (if-not ctx
                (.send (.code reply 401) (clj->js {:error "Unauthorized"}))
                (let [limit (js/parseInt (or (aget (aget req "query") "limit") "50"))
                      offset (js/parseInt (or (aget (aget req "query") "offset") "0"))]
                  (-> (db/query-all-sql
                                          (db/q-select-actors-active {:limit limit :offset offset}))
                      (.then
                        (fn [actors]
                          (.send reply (clj->js {:ok true
                                                   :actors (map sanitize-actor actors)
                                                   :count (count actors)}))))))))))))

  (.get app "/api/actors/:id"
    (fn [req reply]
      (-> (session/resolve-auth-context req)
          (.then
            (fn [ctx]
              (if-not ctx
                (.send (.code reply 401) (clj->js {:error "Unauthorized"}))
                (let [actor-id (aget (aget req "params") "id")]
                  (-> (db/query-one-sql
                        (db/q-select-actor-by-id {:id actor-id}))
                      (.then
                        (fn [actor]
                          (if-not actor
                            (.send (.code reply 404) (clj->js {:error "Actor not found"}))
                            (.send reply (clj->js {:ok true
                                                     :actor (sanitize-actor (js->clj actor :keywordize-keys true))}))))))))))))

  (.get app "/api/actors/me"
    (fn [req reply]
      (-> (session/resolve-auth-context req)
          (.then
            (fn [ctx]
              (if-not ctx
                (.send (.code reply 401) (clj->js {:error "Unauthorized"}))
                (-> (db/query-one-sql
                        (db/q-select-actor-by-id {:id (:auth/actor-id ctx)}))
                    (.then
                      (fn [actor]
                        (if-not actor
                          (.send (.code reply 404) (clj->js {:error "Actor not found"}))
                          (.send reply (clj->js {:ok true
                                                   :actor (sanitize-actor (js->clj actor :keywordize-keys true))}))))))))))))

  (.get app "/api/entities/:id"
    (fn [req reply]
      (-> (session/resolve-auth-context req)
          (.then
            (fn [ctx]
              (if-not ctx
                (.send (.code reply 401) (clj->js {:error "Unauthorized"}))
                (let [entity-id (aget (aget req "params") "id")]
                  (-> (db/query-one-sql
                        (db/q-select-entity-by-id {:id entity-id}))
                      (.then
                        (fn [entity]
                          (if-not entity
                            (.send (.code reply 404) (clj->js {:error "Entity not found"}))
                            (.send reply (clj->js {:ok true
                                                     :entity (js->clj entity :keywordize-keys true)})))))))))))))

  (.post app "/api/actors/:id/capabilities"
    (fn [req reply]
      (-> (session/resolve-auth-context req)
          (.then
            (fn [ctx]
              (if-not ctx
                (.send (.code reply 401) (clj->js {:error "Unauthorized"}))
                (let [actor-id (aget (aget req "params") "id")
                      body (js->clj (or (aget req "body") #js {}) :keywordize-keys true)
                      capabilities (:capabilities body)]
                  (-> (db/query-sql
                                          (db/q-update-actor-capabilities actor-id capabilities))
                      (.then
                        (fn [_]
                          (.send reply (clj->js {:ok true})))))))))))))
)