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
  "Active actors, newest first. `after` is a keyset cursor (the id of the last
   actor on the previous page): the page continues strictly after that row's
   (created_at, id), so revocations elsewhere cannot shift rows past the
   reader the way OFFSET pages do. The cursor row is looked up regardless of
   status because revocation only changes `status`."
  [{:keys [limit offset after]}]
  (cond-> (-> (h/select :a.* [:e.kind :kind])
              (h/from [:actors :a])
              (h/join [:entities :e] [:= :e.id :a.entity_id])
              (h/where [:= :a.status "active"])
              (h/order-by [:a.created_at :desc] [:a.id :desc])
              (h/limit limit))
    (some? after)
    (h/where [:< [:composite :a.created_at :a.id]
              {:select [:c.created_at :c.id]
               :from [[:actors :c]]
               :where [:= :c.id after]}])
    (and (nil? after) (some? offset) (pos? offset))
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
         ;; NOT NULL: select-actors-active keys its cursor on (created_at, id).
         [:created_at :timestamptz :not-null :default [:raw "NOW()"]]
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

(defn create-index-actors-status-created-id
  "Composite index matching select-actors-active's keyset scan
   (status filter, ORDER BY created_at DESC, id DESC, row-value cursor)."
  []
  "CREATE INDEX IF NOT EXISTS idx_actors_status_created_id ON actors(status, created_at DESC, id DESC)")

(defn select-actors-created-at-nullable
  "Report whether the application's actors.created_at column still permits NULL
   (tables created before the NOT NULL constraint)."
  []
  (str "SELECT is_nullable FROM information_schema.columns "
       "WHERE table_schema = current_schema() AND table_name = 'actors' "
       "AND column_name = 'created_at'"))

(defn backfill-actors-created-at
  "Give legacy NULL created_at rows a timestamp so the keyset cursor can order them."
  []
  "UPDATE actors SET created_at = COALESCE(updated_at, NOW()) WHERE created_at IS NULL")

(defn lock-actors-for-created-at
  "Block actor writes during the backfill and SET NOT NULL (which needs
   ACCESS EXCLUSIVE anyway), so no NULL row lands between the two."
  []
  "LOCK TABLE actors IN ACCESS EXCLUSIVE MODE")

(defn enforce-actors-created-at-not-null
  []
  "ALTER TABLE actors ALTER COLUMN created_at SET NOT NULL")

(defn create-index-provider-subject []
  "CREATE UNIQUE INDEX IF NOT EXISTS idx_provider_bindings_subject ON provider_bindings(provider, subject)")

(defn select-actors-email-index
  "Describe idx_actors_email on the application's actors table only.
   Scoped by indrelid so a same-named index in another schema is ignored;
   qualified_name is the quoted schema.index for a targeted DROP."
  []
  (str "SELECT i.indisunique AS is_unique, "
       "format('%I.%I', n.nspname, c.relname) AS qualified_name "
       "FROM pg_index i "
       "JOIN pg_class c ON c.oid = i.indexrelid "
       "JOIN pg_namespace n ON n.oid = c.relnamespace "
       "WHERE c.relname = 'idx_actors_email' "
       "AND i.indrelid = to_regclass('actors')"))

(defn lock-actors-for-email-index
  "Block actor writes (and concurrent migrators) for the email-index swap.
   ACCESS EXCLUSIVE up front because DROP INDEX needs it anyway; taking it
   first avoids a lock upgrade that could deadlock with in-flight requests."
  []
  "LOCK TABLE actors IN ACCESS EXCLUSIVE MODE")

(defn select-duplicate-actor-emails
  "List non-NULL emails shared by more than one actor."
  []
  (str "SELECT email, COUNT(*)::int AS n FROM actors "
       "WHERE email IS NOT NULL GROUP BY email HAVING COUNT(*) > 1"))

(defn drop-index-actors-email
  "Remove the legacy non-unique email index so the unique index can replace it.
   qualified-name comes from select-actors-email-index (already identifier-quoted)."
  [qualified-name]
  (str "DROP INDEX IF EXISTS " qualified-name))

(defn create-index-actors-email
  []
  "CREATE UNIQUE INDEX IF NOT EXISTS idx_actors_email ON actors(email)")

(defn create-index-sessions-actor
  []
  "CREATE INDEX IF NOT EXISTS idx_sessions_actor ON sessions(actor_id)")

(defn create-index-sessions-expires
  []
  "CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at)")
