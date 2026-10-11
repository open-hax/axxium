(ns axxium.server
  "Start the independent identity provider only after schema and configuration checks."
  (:require [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.extern.http :as http]
            [axxium.extern.runtime :as runtime]
            [axxium.infra.identity :as identity]
            [axxium.routes.actor :as actor-routes]
            [axxium.routes.auth :as auth-routes]
            [axxium.routes.health :as health-routes]))
(defn ^:async start! "Initialize persistence once and serve the identity portal." []
  (try
    (cfg/validate!)
    (await (db/init-schema!))
    (await (identity/initialize!))
    (let [app (await (http/create! (cfg/get-in-config [:axxium/public-base-url])))]
      (health-routes/register-health-routes! app)
      (auth-routes/register-auth-routes! app)
      (actor-routes/register-actor-routes! app)
      (await (http/serve-static! app))
      (await (http/listen! app (cfg/get-in-config [:axxium/host]) (cfg/get-in-config [:axxium/port])))
      (println "Axxium identity provider ready"))
    (catch :default _ (println "Axxium startup failed; check runtime configuration and database availability")
      (runtime/fatal!))))
