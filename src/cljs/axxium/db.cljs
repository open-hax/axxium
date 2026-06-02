(ns axxium.db
  "PostgreSQL database layer for Axxium.
   Uses extern.pg for JS interop and HoneySQL for query building.
   Following knoxx patterns."
  (:require [axxium.config :as cfg]
            [axxium.extern.pg :as pg]
            [axxium.shape.db :as q]
            [honey.sql :as sql]))

(defonce pool
  (delay
    (pg/create-pool!
     {:connection-string (cfg/db-url)
      :max 20
      :idle-timeout-ms 30000
      :connect-timeout-ms 2000})))

(defn- honey->sql
  "Format a HoneySQL map to [sql-str params]."
  [honey-map]
  (sql/format honey-map {:numbered true}))

(defn query-sql
  "Execute a HoneySQL query. Returns promise of rows."
  [honey-map]
  (let [[sql-str params] (honey->sql honey-map)]
    (-> (pg/query! @pool sql-str params)
        (.then :rows))))

(defn query-one-sql
  "Execute HoneySQL query and return first row or nil."
  [honey-map]
  (let [[sql-str params] (honey->sql honey-map)]
    (pg/query-one! @pool sql-str params)))

(defn query-all-sql
  "Execute HoneySQL query and return all rows."
  [honey-map]
  (query-sql honey-map))

(defn- exec-ddl!
  "Execute a HoneySQL DDL statement."
  [honey-map]
  (let [[sql-str _params] (honey->sql honey-map)]
    (pg/query! @pool sql-str [])))

(defn init-schema!
  "Initialize database schema. Idempotent."
  []
  (-> (exec-ddl! (q/create-table-entities))
      (.then (fn [_] (exec-ddl! (q/create-table-actors))))
      (.then (fn [_] (exec-ddl! (q/create-table-sessions))))
      (.then (fn [_] (exec-ddl! (q/create-table-oauth-clients))))
      (.then (fn [_] (pg/query! @pool (q/create-index-actors-email) [])))
      (.then (fn [_] (pg/query! @pool (q/create-index-sessions-actor) [])))
      (.then (fn [_] (pg/query! @pool (q/create-index-sessions-expires) [])))))

;; Re-export query builders for convenience
(def q-select-actor-by-id q/select-actor-by-id)
(def q-select-actor-by-email q/select-actor-by-email)
(def q-select-actor-by-email-active q/select-actor-by-email-active)
(def q-select-actors-active q/select-actors-active)
(def q-insert-actor q/insert-actor)
(def q-insert-entity q/insert-entity)
(def q-select-entity-by-id q/select-entity-by-id)
(def q-update-actor-capabilities q/update-actor-capabilities)
(def q-insert-session q/insert-session)
(def q-select-actor-by-session q/select-actor-by-session)
(def q-delete-session-by-hash q/delete-session-by-hash)
(def q-health-check q/health-check)
