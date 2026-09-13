(ns axxium.infra.identity
  "Atomic account registration and recipient-side transfer replay protection."
  (:require [axxium.db :as db]
            [axxium.extern.pg :as pg]
            [axxium.shape.db :as q]
            [honey.sql :as sql]))
(defn- execute! [conn query]
  (let [[statement & params] (sql/format query {:numbered true})]
    (pg/query! conn statement params)))
(defn ^:async initialize! "Add identity provenance and single-use transfer storage." []
  (await (pg/query! @db/pool "ALTER TABLE actors ADD COLUMN IF NOT EXISTS origin_issuer text" []))
  (await (pg/query! @db/pool "CREATE UNIQUE INDEX IF NOT EXISTS actors_unique_email ON actors(lower(email))" []))
  (await (pg/query! @db/pool "CREATE TABLE IF NOT EXISTS identity_transfers (issuer text NOT NULL, nonce text NOT NULL, actor_id text NOT NULL, consumed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(issuer,nonce))" [])))
(defn register! "Commit entity, actor and optional transfer receipt in one transaction." [actor issuer nonce]
  (pg/transaction! @db/pool
    (^:async fn [conn]
      (when nonce
        (await (pg/query! conn "INSERT INTO identity_transfers(issuer,nonce,actor_id) VALUES($1,$2,$3)"
                          [issuer nonce (:id actor)])))
      (await (execute! conn (q/insert-entity {:id (:entity-id actor) :kind "human"
                                             :email (:email actor) :display-name (:display-name actor)})))
      (await (execute! conn (q/insert-actor actor)))
      (await (pg/query! conn "UPDATE actors SET origin_issuer=$1 WHERE id=$2" [issuer (:id actor)]))
      (await (pg/query-one! conn "SELECT * FROM actors WHERE id=$1" [(:id actor)])))))
