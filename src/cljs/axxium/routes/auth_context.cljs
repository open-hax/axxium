(ns axxium.routes.auth-context
  "Shared authentication and administrator admission for protected routes."
  (:require [axxium.auth.session :as session]
            [axxium.db :as db]
            [axxium.extern.http :as http]
            [axxium.law.actor :as law]))

(defn with-auth
  "Call the handler with a verified context, or return the shared 401 response."
  [handler]
  (fn ^:async authenticated-handler [req reply]
    (try
      (if-let [context (await (session/resolve-auth-context req))]
        (await (handler req reply context))
        (http/send! reply 401 {:error "Unauthorized"}))
      (catch :default error
        (println "Protected route failed:" (http/error-message error))
        (http/send! reply 500 {:error "Could not complete protected request"})))))

(defn with-admin
  "Admit human system administrators; distinguish absent and insufficient auth."
  [handler]
  (with-auth
   (fn ^:async administrator-handler [req reply context]
     (let [entity (await (db/query-one-sql
                          (db/q-select-entity-for-actor (:auth/actor-id context))))]
       (if (law/system-admin? context entity)
         (handler req reply context)
         (http/send! reply 403 {:error "System administrator required"}))))))
