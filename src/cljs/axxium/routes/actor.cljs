(ns axxium.routes.actor
  "Authenticated actor registry and administrator account controls."
  (:require [axxium.auth.session :as session]
            [axxium.db :as db]
            [axxium.extern.http :as http]
            [axxium.law.actor :as law]))

(defn- sanitize-actor [actor]
  (dissoc actor :password_hash))

(defn- with-auth [handler]
  (fn [req reply]
    (-> (session/resolve-auth-context req)
        (.then (fn [context]
                 (if context
                   (handler req reply context)
                   (http/send! reply 401 {:error "Unauthorized"})))))))

(defn- with-admin [handler]
  (with-auth
   (fn [req reply context]
     (-> (db/query-one-sql
          (db/q-select-entity-for-actor (:auth/actor-id context)))
         (.then (fn [entity]
                  (if (law/system-admin? context entity)
                    (handler req reply context)
                    (http/send! reply 403
                                {:error "System administrator required"}))))))))

(defn- pagination [req]
  (let [limit (or (http/parse-int (http/query-param req "limit")) 50)
        offset (or (http/parse-int (http/query-param req "offset")) 0)]
    {:limit (min 100 (max 1 limit)) :offset (max 0 offset)}))

(defn- register-list-actors-route! [app]
  (http/get! app "/api/actors"
             (with-admin
              (fn [req reply _context]
                (-> (db/query-all-sql (db/q-select-actors-active (pagination req)))
                    (.then (fn [actors]
                             (http/send! reply 200
                                         {:ok true
                                          :actors (mapv sanitize-actor actors)
                                          :count (count actors)}))))))))

(defn- register-get-actor-route! [app]
  (http/get! app "/api/actors/:id"
             (with-admin
              (fn [req reply _context]
                (-> (db/query-one-sql
                     (db/q-select-actor-by-id {:id (http/param req "id")}))
                    (.then (fn [actor]
                             (if actor
                               (http/send! reply 200
                                           {:ok true :actor (sanitize-actor actor)})
                               (http/send! reply 404 {:error "Actor not found"})))))))))

(defn- register-get-me-route! [app]
  (http/get! app "/api/actors/me"
             (with-auth
              (fn [_req reply context]
                (-> (db/query-one-sql
                     (db/q-select-actor-by-id {:id (:auth/actor-id context)}))
                    (.then (fn [actor]
                             (if actor
                               (http/send! reply 200
                                           {:ok true :actor (sanitize-actor actor)})
                               (http/send! reply 404 {:error "Actor not found"})))))))))

(defn- register-get-entity-route! [app]
  (http/get! app "/api/entities/:id"
             (with-admin
              (fn [req reply _context]
                (-> (db/query-one-sql
                     (db/q-select-entity-by-id {:id (http/param req "id")}))
                    (.then (fn [entity]
                             (if entity
                               (http/send! reply 200 {:ok true :entity entity})
                               (http/send! reply 404 {:error "Entity not found"})))))))))

(defn- register-update-capabilities-route! [app]
  (http/post! app "/api/actors/:id/capabilities"
              (with-admin
               (fn [req reply _context]
                 (let [capabilities (:capabilities (http/body req))]
                   (if (and (vector? capabilities)
                            (<= (count capabilities) 64)
                            (every? string? capabilities))
                     (-> (db/query-sql
                          (db/q-update-actor-capabilities
                           (http/param req "id") capabilities))
                         (.then (fn [_] (http/send! reply 200 {:ok true}))))
                     (http/send! reply 400
                                 {:error "capabilities must be a list of strings"})))))))

(defn register-actor-routes! [app]
  (register-list-actors-route! app)
  (register-get-actor-route! app)
  (register-get-me-route! app)
  (register-get-entity-route! app)
  (register-update-capabilities-route! app))
