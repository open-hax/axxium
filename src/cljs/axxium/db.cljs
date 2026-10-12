(ns axxium.db
  "PostgreSQL database layer for Axxium.
   Uses extern.pg for JS interop and HoneySQL for query building.
   Following knoxx patterns."
  (:require [axxium.config :as cfg]
            [axxium.extern.pg :as pg]
            [axxium.extern.json :as json]
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
  (let [formatted (sql/format honey-map {:numbered true})]
    [(first formatted) (rest formatted)]))

(defn- ^:async query-on!
  "Execute a HoneySQL query through one opaque pool/client handle."
  [connection honey-map]
  (let [[sql-str params] (honey->sql honey-map)
        {:keys [rows]} (await (pg/query! connection sql-str params))]
    rows))

(defn query-sql
  "Execute a HoneySQL query. Returns promise of rows."
  [honey-map]
  (query-on! @pool honey-map))

(defn with-transaction!
  "Run an operation with a HoneySQL query function bound to one transaction."
  [operation]
  (pg/with-transaction!
   @pool
   (fn [connection]
     (operation (fn [honey-map] (query-on! connection honey-map))))))

(defn provider-binding-conflict?
  "Recognize only the provider/subject uniqueness conflict for sign-in recovery."
  [error]
  (pg/provider-binding-conflict? error))

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
      (.then (fn [_] (exec-ddl! (q/create-table-agent-credentials))))
      (.then (fn [_] (exec-ddl! (q/create-table-provider-bindings))))
      (.then (fn [_] (pg/query! @pool (q/create-index-provider-subject) [])))
      (.then (fn [_] (pg/query! @pool (q/create-index-actors-email) [])))
      (.then (fn [_] (pg/query! @pool (q/create-index-sessions-actor) [])))
      (.then (fn [_] (pg/query! @pool (q/create-index-sessions-expires) [])))))

;; Re-export query builders for convenience
(def q-select-actor-by-id q/select-actor-by-id)
(def q-select-actor-by-email q/select-actor-by-email)
(def q-select-actor-by-email-active q/select-actor-by-email-active)
(def q-select-entity-for-actor q/select-entity-for-actor)
(def q-select-active-agent-credential q/select-active-agent-credential)
(def q-select-actor-by-provider-subject q/select-actor-by-provider-subject)
(def q-insert-provider-binding q/insert-provider-binding)
(def q-insert-agent-credential q/insert-agent-credential)
(def q-revoke-agent-credential q/revoke-agent-credential)
(def q-select-agent-credentials q/select-agent-credentials)
(def q-select-actors-active q/select-actors-active)
(defn q-insert-actor
  "Build an actor insert with JSON-encoded capabilities and roles."
  [actor]
  (q/insert-actor
   (-> actor
       (assoc :capabilities-json (json/encode (:capabilities actor)))
       (assoc :roles-json (json/encode (:roles actor))))))
(def q-insert-entity q/insert-entity)
(def q-select-entity-by-id q/select-entity-by-id)
(defn q-update-actor-capabilities
  "Build an actor capabilities update with JSON encoding at the boundary."
  [id capabilities]
  (q/update-actor-capabilities id (json/encode capabilities)))
(def q-insert-session q/insert-session)
(def q-select-actor-by-session q/select-actor-by-session)
(def q-delete-session-by-hash q/delete-session-by-hash)
(def q-health-check q/health-check)
