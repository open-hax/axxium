(ns axxium.infra.oidc
  "Compose the optional authority with Axxium-owned root sessions and actors."
  (:require [axxium.auth.session :as session]
            [axxium.config :as config]
            [axxium.db :as db]
            [axxium.domain.github-identity :as actor-tree]
            [axxium.extern.env :as environment]
            [axxium.extern.oidc-http :as transport]
            [axxium.routes.github-identity :as identity]))

(defn enable!
  "Mount only when the operator supplies a private OIDC configuration file."
  [app]
  (let [mailbox-did (config/get-in-config [:mailbox/webhook-actor-did])
        bound-actor-did (get (config/get-in-config [:mailbox/actor-bindings]) mailbox-did)
        {:keys [actor-id root-did]} (config/get-in-config [:identity/root-operator-binding])]
    (transport/install! app @db/pool
      {:config-file (environment/get-value "AXXIUM_OIDC_CONFIG_FILE")
       :public-base-url (config/get-in-config [:axxium/public-base-url])
       :mailbox-actor-did mailbox-did
       :mailbox-bound-actor-did bound-actor-did
       :mailbox-owner-authority
       (when actor-id
         {:owner-actor-id actor-id :root-did root-did
          :resolve-human-session! session/human-session-proof!
          :authorized? (fn []
                         (actor-tree/active-owner-delegation? (identity/current-actor-state)
                           root-did bound-actor-did))})
       :mailbox-available? (and (some? (config/get-in-config [:mailbox/ledger-file]))
                                (some? (config/get-in-config [:mailbox/webhook-key-file])))
       :session-cookie-name (config/get-in-config [:session/cookie-name])}
      session/verify-session
      (fn [subject] (db/query-one-sql (db/q-select-actor-by-id {:id subject}))))))
