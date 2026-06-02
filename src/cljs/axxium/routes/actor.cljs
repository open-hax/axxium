(ns axxium.routes.actor
  "Actor registry routes for Axxium."
  (:require [axxium.db :as db]
            [axxium.auth.session :as session]))

(defn- sanitize-actor [actor]
  (dissoc actor :password_hash))

(defn- with-auth [handler-fn]
  (fn [req reply]
    (-> (session/resolve-auth-context req)
        (.then
          (fn [ctx]
            (if-not ctx
              (.send (.code reply 401) (clj->js {:error "Unauthorized"}))
              (handler-fn req reply ctx)))))))

(defn- clamp-pagination [limit offset]
  (let [parsed-limit (js/parseInt limit 10)
        parsed-offset (js/parseInt offset 10)
        safe-limit (if (or (js/Number.isNaN parsed-limit) (< parsed-limit 1)) 50 parsed-limit)
        safe-offset (if (or (js/Number.isNaN parsed-offset) (< parsed-offset 0)) 0 parsed-offset)
        clamped-limit (js/Math.min safe-limit 100)]
    [clamped-limit safe-offset]))

(defn- register-list-actors-route! [app]
  (.get app "/api/actors"
    (with-auth
      (fn [req reply _ctx]
        (let [limit (or (aget (aget req "query") "limit") "50")
              offset (or (aget (aget req "query") "offset") "0")
              [clamped-limit safe-offset] (clamp-pagination limit offset)]
          (-> (db/query-all-sql
                (db/q-select-actors-active {:limit clamped-limit :offset safe-offset}))
              (.then
                (fn [actors]
                  (.send reply (clj->js {:ok true
                                         :actors (map sanitize-actor actors)
                                         :count (count actors)}))))))))))

(defn- register-get-actor-route! [app]
  (.get app "/api/actors/:id"
    (with-auth
      (fn [req reply _ctx]
        (let [actor-id (aget (aget req "params") "id")]
          (-> (db/query-one-sql
                (db/q-select-actor-by-id {:id actor-id}))
              (.then
                (fn [actor]
                  (if-not actor
                    (.send (.code reply 404) (clj->js {:error "Actor not found"}))
                    (.send reply (clj->js {:ok true
                                           :actor (sanitize-actor (js->clj actor :keywordize-keys true))}))))))))))

(defn- register-get-me-route! [app]
  (.get app "/api/actors/me"
    (with-auth
      (fn [_req reply ctx]
        (-> (db/query-one-sql
              (db/q-select-actor-by-id {:id (:auth/actor-id ctx)}))
            (.then
              (fn [actor]
                (if-not actor
                  (.send (.code reply 404) (clj->js {:error "Actor not found"}))
                  (.send reply (clj->js {:ok true
                                         :actor (sanitize-actor (js->clj actor :keywordize-keys true))}))))))))))

(defn- register-get-entity-route! [app]
  (.get app "/api/entities/:id"
    (with-auth
      (fn [req reply _ctx]
        (let [entity-id (aget (aget req "params") "id")]
          (-> (db/query-one-sql
                (db/q-select-entity-by-id {:id entity-id}))
              (.then
                (fn [entity]
                  (if-not entity
                    (.send (.code reply 404) (clj->js {:error "Entity not found"}))
                    (.send reply (clj->js {:ok true
                                           :entity (js->clj entity :keywordize-keys true)})))))))))))

(defn- register-update-capabilities-route! [app]
  (.post app "/api/actors/:id/capabilities"
    (with-auth
      (fn [req reply ctx]
        (let [actor-id (aget (aget req "params") "id")
              body (js->clj (or (aget req "body") #js {}) :keywordize-keys true)
              capabilities (:capabilities body)]
          (-> (db/query-sql
                (db/q-update-actor-capabilities actor-id capabilities))
              (.then
                (fn [_]
                  (.send reply (clj->js {:ok true})))))))))))

(defn register-actor-routes! [app]
  (register-list-actors-route! app)
  (register-get-actor-route! app)
  (register-get-me-route! app)
  (register-get-entity-route! app)
  (register-update-capabilities-route! app))
