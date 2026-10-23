(ns axxium.infra.oidc
  "Compose the optional authority with Axxium-owned root sessions and actors."
  (:require [axxium.auth.session :as session]
            [axxium.config :as config]
            [axxium.db :as db]
            [axxium.extern.env :as environment]
            [axxium.extern.oidc-http :as transport]))

(defn enable!
  "Mount only when the operator supplies a private OIDC configuration file."
  [app]
  (transport/install! app @db/pool
    {:config-file (environment/get-value "AXXIUM_OIDC_CONFIG_FILE")
     :public-base-url (config/get-in-config [:axxium/public-base-url])
     :session-cookie-name (config/get-in-config [:session/cookie-name])}
    session/verify-session
    (fn [subject] (db/query-one-sql (db/q-select-actor-by-id {:id subject})))))
