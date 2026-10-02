(ns axxium.shape.db
  "Pure HoneySQL query builders for Axxium. No execution.
   Following knoxx.backend.shape.db.* patterns."
  (:require [honey.sql.helpers :as h]))

;; ---------------------------------------------------------------------------
;; Entities
;; ---------------------------------------------------------------------------

(defn insert-entity
  [{:keys [id kind email display-name]}]
  (-> (h/insert-into :entities)
      (h/values [{:id id
                  :kind kind
                  :email email
                  :display_name display-name}])
      (h/returning :*)))

(defn select-entity-by-id
  [{:keys [id]}]
  (-> (h/select :*)
      (h/from :entities)
      (h/where [:= :id id])))

;; ---------------------------------------------------------------------------
;; Actors
;; ---------------------------------------------------------------------------

(defn insert-actor
  [{:keys [id entity-id email display-name password-hash capabilities-json roles-json status]}]
  (-> (h/insert-into :actors)
      (h/values [{:id id
                  :entity_id entity-id
                  :email email
                  :display_name display-name
                  :password_hash password-hash
                  :capabilities [:cast capabilities-json :jsonb]
                  :roles [:cast roles-json :jsonb]
                  :status status}])
      (h/returning :*)))

(defn select-actor-by-id
  [{:keys [id]}]
  (-> (h/select :*)
      (h/from :actors)
      (h/where [:= :id id])))

(defn select-actor-by-email
  [{:keys [email]}]
  (-> (h/select :*)
      (h/from :actors)
      (h/where [:= :email email])))

(defn select-actor-by-email-active
  [{:keys [email]}]
  (-> (h/select :*)
      (h/from :actors)
      (h/where [:and
                [:= :email email]
                [:= :status "active"]])))

(defn select-entity-for-actor [actor-id]
  (-> (h/select :e.*)
      (h/from [:entities :e])
      (h/join [:actors :a] [:= :a.entity_id :e.id])
      (h/where [:= :a.id actor-id])))

(defn select-active-agent-credential [token-hash]
  (-> (h/select :a.*)
      (h/from [:agent_credentials :c])
      (h/join [:actors :a] [:= :a.id :c.actor_id])
      (h/join [:entities :e] [:= :e.id :a.entity_id])
      (h/where [:and [:= :c.token_hash token-hash]
                [:= :a.status "active"]
                [:= :e.kind "agent"]
                [:= :c.revoked_at nil]
                [:> :c.expires_at [:now]]])))

(defn select-actor-by-provider-subject [provider subject]
  (-> (h/select :a.*)
      (h/from [:actors :a])
      (h/join [:provider_bindings :b] [:= :b.actor_id :a.id])
      (h/where [:and [:= :b.provider provider]
                [:= :b.subject subject]
                [:= :a.status "active"]])))

(defn insert-provider-binding [provider subject actor-id]
  (-> (h/insert-into :provider_bindings)
      (h/values [{:provider provider :subject subject :actor_id actor-id}])))

(defn insert-agent-credential [{:keys [id actor-id token-hash expires-at label issued-by]}]
  (-> (h/insert-into :agent_credentials)
      (h/values [{:id id :actor_id actor-id :token_hash token-hash
                  :expires_at expires-at :label label :issued_by issued-by}])
      (h/returning :id :actor_id :label :expires_at :created_at)))

(defn revoke-agent-credential [id actor-id]
  (-> (h/update :agent_credentials)
      (h/set {:revoked_at [:now]})
      (h/where [:and [:= :id id] [:= :actor_id actor-id] [:= :revoked_at nil]])
      (h/returning :id)))

(defn select-agent-credentials [actor-id]
  (-> (h/select :id :label :expires_at :created_at :revoked_at)
      (h/from :agent_credentials)
      (h/where [:= :actor_id actor-id])
      (h/order-by [:created_at :desc])))

(defn select-actors-active
  [{:keys [limit offset]}]
  (-> (h/select :a.* [:e.kind :kind])
      (h/from [:actors :a])
      (h/join [:entities :e] [:= :e.id :a.entity_id])
      (h/where [:= :a.status "active"])
      (h/order-by [:a.created_at :desc])
      (h/limit limit)
      (h/offset offset)))

(defn update-actor-capabilities
  [id capabilities-json]
  (-> (h/update :actors)
      (h/set {:capabilities [:cast capabilities-json :jsonb]
              :updated_at [:now]})
      (h/where [:= :id id])))

;; ---------------------------------------------------------------------------
;; Sessions
;; ---------------------------------------------------------------------------

(defn insert-session
  [{:keys [actor-id token-hash expires-at]}]
  (-> (h/insert-into :sessions)
      (h/values [{:actor_id actor-id
                  :token_hash token-hash
                  :expires_at expires-at}])
      (h/returning :*)))

(defn select-actor-by-session
  [actor-id token-hash]
  (-> (h/select :a.*)
      (h/from [:actors :a])
      (h/join [:sessions :s] [:= :a.id :s.actor_id])
      (h/where [:and
                [:= :a.id actor-id]
                [:= :s.token_hash token-hash]
                [:= :a.status "active"]
                [:> :s.expires_at [:now]]])))

(defn delete-session-by-hash
  [token-hash]
  (-> (h/delete-from :sessions)
      (h/where [:= :token_hash token-hash])))

;; ---------------------------------------------------------------------------
;; Health
;; ---------------------------------------------------------------------------

(defn health-check
  []
  (-> (h/select [1 :ping])))

;; ---------------------------------------------------------------------------
;; Schema DDL
;; ---------------------------------------------------------------------------

(defn create-table-entities
  []
  (-> (h/create-table :entities :if-not-exists)
      (h/with-columns
        [[:id :text :primary-key]
         [:kind :text :not-null]
         [:email :text]
         [:display_name :text]
         [:created_at :timestamptz :default [:raw "NOW()"]]])))

(defn create-table-actors
  []
  (-> (h/create-table :actors :if-not-exists)
      (h/with-columns
        [[:id :text :primary-key]
         [:entity_id :text :not-null :references [:entities :id]]
         [:email :text]
         [:display_name :text]
         [:password_hash :text]
         [:capabilities :jsonb :default [:raw "'[]'"]]
         [:roles :jsonb :default [:raw "'[]'"]]
         [:status :text :default "active"]
         [:created_at :timestamptz :default [:raw "NOW()"]]
         [:updated_at :timestamptz :default [:raw "NOW()"]]])))

(defn create-table-sessions
  []
  (-> (h/create-table :sessions :if-not-exists)
      (h/with-columns
        [[:id :uuid :primary-key :default [:raw "gen_random_uuid()"]]
         [:actor_id :text :not-null :references [:actors :id]]
         [:token_hash :text :not-null]
         [:expires_at :timestamptz :not-null]
         [:created_at :timestamptz :default [:raw "NOW()"]]])))

(defn create-table-oauth-clients
  []
  (-> (h/create-table :oauth_clients :if-not-exists)
      (h/with-columns
        [[:id :text :primary-key]
         [:secret_hash :text :not-null]
         [:name :text :not-null]
         [:redirect_uris :jsonb :default [:raw "'[]'"]]
         [:grant_types :jsonb :default [:raw "'[]'"]]
         [:scopes :jsonb :default [:raw "'[]'"]]
         [:created_at :timestamptz :default [:raw "NOW()"]]])))

(defn create-table-agent-credentials []
  (-> (h/create-table :agent_credentials :if-not-exists)
      (h/with-columns
        [[:id :uuid :primary-key]
         [:actor_id :text :not-null :references [:actors :id]]
         [:token_hash :text :not-null :unique]
         [:label :text :not-null]
         [:issued_by :text :not-null :references [:actors :id]]
         [:expires_at :timestamptz :not-null]
         [:revoked_at :timestamptz]
         [:created_at :timestamptz :default [:raw "NOW()"]]])))

(defn create-table-provider-bindings []
  (-> (h/create-table :provider_bindings :if-not-exists)
      (h/with-columns
        [[:id :uuid :primary-key :default [:raw "gen_random_uuid()"]]
         [:provider :text :not-null]
         [:subject :text :not-null]
         [:actor_id :text :not-null :references [:actors :id]]
         [:created_at :timestamptz :default [:raw "NOW()"]]])))

(defn create-index-provider-subject []
  "CREATE UNIQUE INDEX IF NOT EXISTS idx_provider_bindings_subject ON provider_bindings(provider, subject)")

(defn create-index-actors-email
  []
  "CREATE INDEX IF NOT EXISTS idx_actors_email ON actors(email)")

(defn create-index-sessions-actor
  []
  "CREATE INDEX IF NOT EXISTS idx_sessions_actor ON sessions(actor_id)")

(defn create-index-sessions-expires
  []
  "CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at)")
