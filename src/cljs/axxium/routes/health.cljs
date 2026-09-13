(ns axxium.routes.health
  "Database-backed health check with no credential disclosure."
  (:require [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.extern.http :as http]))
(defn register-health-routes! "Report ready only when the local database responds." [app]
  (http/route! app "GET" "/health"
    (^:async fn [_] (await (db/query-one-sql (db/q-health-check)))
      {:body {:ok true :service "axxium" :instance (cfg/get-in-config [:axxium/public-base-url])}})
    cfg/config))
