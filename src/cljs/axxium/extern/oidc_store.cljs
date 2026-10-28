(ns axxium.extern.oidc-store
  "PostgreSQL adapter for oidc-provider's opaque protocol records.
   Does not accept a product session or infer an actor from an email."
  (:require [axxium.extern.pg :as pg]
            [axxium.law.device-polling :as polling]
            ["oidc-provider" :refer [errors]]))

(def schema-sql
  "Private protocol store with expiry and lookup indexes; additive schema only."
  ["CREATE TABLE IF NOT EXISTS axxium_oidc_records (model text NOT NULL, id text NOT NULL, payload jsonb NOT NULL, expires_at timestamptz NOT NULL, consumed_at bigint, PRIMARY KEY (model,id))"
   "ALTER TABLE axxium_oidc_records ADD COLUMN IF NOT EXISTS poll_last_ms bigint"
   "ALTER TABLE axxium_oidc_records ADD COLUMN IF NOT EXISTS poll_interval integer NOT NULL DEFAULT 5"
   "ALTER TABLE axxium_oidc_records ADD COLUMN IF NOT EXISTS poll_count integer NOT NULL DEFAULT 0"
   "CREATE INDEX IF NOT EXISTS axxium_oidc_grant ON axxium_oidc_records ((payload->>'grantId'))"
   "CREATE INDEX IF NOT EXISTS axxium_oidc_user_code ON axxium_oidc_records (model,(payload->>'userCode'))"
   "CREATE INDEX IF NOT EXISTS axxium_oidc_uid ON axxium_oidc_records (model,(payload->>'uid'))"
   "CREATE INDEX IF NOT EXISTS axxium_oidc_expiry ON axxium_oidc_records (expires_at)"
   "CREATE TABLE IF NOT EXISTS axxium_oidc_grant_sessions (grant_id text PRIMARY KEY, actor_id text NOT NULL, token_hash text NOT NULL, expires_at timestamptz NOT NULL)"
   "CREATE INDEX IF NOT EXISTS axxium_oidc_binding_expiry ON axxium_oidc_grant_sessions (expires_at)"])

(defn ^:async cleanup-expired!
  "Reclaim at most 500 rows per table per sweep. Keep consumption evidence until
   one day AFTER protocol expiry; active/recently expired payloads remain intact.
   Concurrent sweepers skip locked rows rather than blocking authentication."
  [pool]
  (await (pg/query! pool
           "WITH expired AS (SELECT model,id FROM axxium_oidc_records WHERE expires_at<clock_timestamp()-interval '1 day' ORDER BY expires_at,model,id LIMIT 500 FOR UPDATE SKIP LOCKED) DELETE FROM axxium_oidc_records r USING expired e WHERE r.model=e.model AND r.id=e.id"
           []))
  (await (pg/query! pool
           "WITH expired AS (SELECT grant_id FROM axxium_oidc_grant_sessions WHERE expires_at<clock_timestamp()-interval '1 day' ORDER BY expires_at,grant_id LIMIT 500 FOR UPDATE SKIP LOCKED) DELETE FROM axxium_oidc_grant_sessions b USING expired e WHERE b.grant_id=e.grant_id"
           [])))

(defn ^:async initialize!
  "Create the dedicated protocol table without altering actor/session tables."
  [pool]
  (doseq [statement schema-sql]
    (await (pg/query! pool statement [])))
  (await (cleanup-expired! pool)))

(defn- decode-record
  "Return an opaque provider payload, including durable consumption evidence."
  [row]
  (when row
    (clj->js (cond-> (:payload row)
               (:consumed_at row) (assoc :consumed (js/Number (:consumed_at row)))))))

(defn- valid-key?
  "Bound opaque provider keys before crossing the database boundary."
  [value]
  (and (string? value) (<= 1 (count value) 2048)))

(defn- require-key!
  "Reject malformed opaque identifiers rather than coerce them into SQL keys."
  [value]
  (when-not (valid-key? value)
    (throw (ex-info "Invalid OIDC store identifier" {})))
  value)

(defn- require-lifetime!
  "Provider lifetimes must be positive whole seconds with a bounded horizon."
  [seconds]
  (when-not (and (integer? seconds) (<= 1 seconds 31536000))
    (throw (ex-info "Invalid OIDC store lifetime" {})))
  seconds)

(defn ^:async bind-session!
  "Bind a fresh grant to a current active root session, using its digest only.
   Never rebind an existing grant to a different session or resurrect a logout."
  [pool grant-id actor-id token-hash]
  (doseq [value [grant-id actor-id]] (require-key! value))
  (when-not (and (string? token-hash) (re-matches #"[0-9a-f]{64}" token-hash))
    (throw (ex-info "OIDC session binding requires a SHA256 digest" {})))
  (let [result (await (pg/query! pool
                       "INSERT INTO axxium_oidc_grant_sessions(grant_id,actor_id,token_hash,expires_at) SELECT $1,a.id,s.token_hash,LEAST(s.expires_at,clock_timestamp()+interval '1 day') FROM actors a JOIN sessions s ON s.actor_id=a.id WHERE a.id=$2 AND s.token_hash=$3 AND a.status='active' AND s.expires_at>clock_timestamp() LIMIT 1 ON CONFLICT(grant_id) DO NOTHING"
                       [grant-id actor-id token-hash]))]
    (when-not (= 1 (:row-count result))
      (throw (new (.-InvalidGrant errors) "A current root session is required")))))

(defn ^:async session-grant-active?
  "Check current actor and root-session state before grant reuse/introspection."
  [pool grant-id actor-id]
  (require-key! grant-id)
  (require-key! actor-id)
  (let [row (await (pg/query-one! pool
                    "SELECT EXISTS(SELECT 1 FROM axxium_oidc_grant_sessions b JOIN actors a ON a.id=b.actor_id JOIN sessions s ON s.actor_id=b.actor_id AND s.token_hash=b.token_hash WHERE b.grant_id=$1 AND b.actor_id=$2 AND b.expires_at>clock_timestamp() AND s.expires_at>clock_timestamp() AND a.status='active') AS active"
                    [grant-id actor-id]))]
    (true? (:active row))))

(defn ^:async session-grant-expiry!
  "Read the current stored root bound; a missing/disabled/revoked parent refuses."
  [pool grant-id actor-id]
  (require-key! grant-id)
  (require-key! actor-id)
  (let [row (await (pg/query-one! pool
                    "SELECT floor(extract(epoch from LEAST(b.expires_at,s.expires_at))*1000) AS expires_ms FROM axxium_oidc_grant_sessions b JOIN actors a ON a.id=b.actor_id JOIN sessions s ON s.actor_id=b.actor_id AND s.token_hash=b.token_hash WHERE b.grant_id=$1 AND b.actor_id=$2 AND b.expires_at>clock_timestamp() AND s.expires_at>clock_timestamp() AND a.status='active'"
                    [grant-id actor-id]))]
    (when-not row
      (throw (new (.-InvalidGrant errors) "A current root session is required")))
    (js/Number (:expires_ms row))))

(defn ^:async revoke-family!
  "Destroy the engine grant as well as its tokens; never permit rotation after refusal."
  [pool grant-id]
  (require-key! grant-id)
  (await (pg/query! pool
           "DELETE FROM axxium_oidc_records WHERE (model='Grant' AND id=$1) OR payload->>'grantId'=$1"
           [grant-id])))

(defn- polling-error
  "Map portable refusal names to the engine's public OAuth error classes."
  [error]
  (let [error-type (case error
                     :slow-down (.-SlowDown errors)
                     :expired-token (.-ExpiredToken errors)
                     :invalid-request (.-InvalidRequest errors)
                     (.-InvalidGrant errors))]
    (new error-type)))

(defn- ^:async find-polled-device!
  "Admit only the engine-authenticated client under one durable row lock.
   Return refusals after COMMIT so slow_down backoff survives the error response.
   Expired device rows are read here so RFC8628 returns expired_token; all other
   model lookups retain the ordinary expiry filter. No opaque code is decoded."
  [pool id authenticated-client-id]
  (let [result
        (await (pg/with-transaction! pool
                 (fn ^:async locked-poll [client]
                   (let [row (await (pg/query-one! client
                                      "SELECT payload,consumed_at,poll_last_ms,poll_interval,poll_count,floor(extract(epoch from expires_at)*1000) AS expiry_ms,floor(extract(epoch from clock_timestamp())*1000) AS now_ms FROM axxium_oidc_records WHERE model='DeviceCode' AND id=$1 FOR UPDATE"
                                      [id]))
                         record (when row
                                  {:client-id (get-in row [:payload :clientId])
                                   :expires-at-ms (js/Number (:expiry_ms row))
                                   :last-poll-ms (when (:poll_last_ms row) (js/Number (:poll_last_ms row)))
                                   :interval-seconds (:poll_interval row)
                                   :poll-count (:poll_count row)
                                   :consumed? (some? (:consumed_at row))})
                         admission (polling/admit-poll record authenticated-client-id
                                     (if row (js/Number (:now_ms row)) (.now js/Date)))]
                     (when-let [updated (:record admission)]
                       (await (pg/query! client
                                "UPDATE axxium_oidc_records SET poll_last_ms=$2,poll_interval=$3,poll_count=$4 WHERE model='DeviceCode' AND id=$1"
                                [id (:last-poll-ms updated) (:interval-seconds updated) (:poll-count updated)])))
                     {:admission admission :row row}))))]
    (if (= :continue (get-in result [:admission :decision]))
      (decode-record (:row result))
      (throw (polling-error (get-in result [:admission :error]))))))

(defn adapter-factory
  "Return oidc-provider's constructor with one model-scoped durable adapter.
   An atomic consume loser throws InvalidGrant, so concurrent code redemption
   cannot issue a second successful response. Queries never interpolate keys."
  ([pool] (adapter-factory pool (constantly nil)))
  ([pool polling-context!]
  (fn [model]
    (require-key! model)
    #js {:upsert
         (fn ^:async upsert-record [id payload expires-in]
           (require-key! id)
           (require-lifetime! expires-in)
           (when (and (= model "DeviceCode") (> expires-in 600))
             (throw (ex-info "Device authorization lifetime exceeds ten minutes" {})))
           (when-not (and (object? payload)
                          (not (.isArray js/Array payload)))
             (throw (ex-info "Invalid OIDC store payload" {})))
           (if (contains? #{"RefreshToken" "AccessToken"} model)
             (let [result (await (pg/query! pool
                                   "INSERT INTO axxium_oidc_records(model,id,payload,expires_at) SELECT $1,$2,$3::jsonb,LEAST(clock_timestamp()+$4*interval '1 second',b.expires_at,s.expires_at) FROM axxium_oidc_grant_sessions b JOIN actors a ON a.id=b.actor_id JOIN sessions s ON s.actor_id=b.actor_id AND s.token_hash=b.token_hash JOIN axxium_oidc_records g ON g.model='Grant' AND g.id=b.grant_id AND g.expires_at>clock_timestamp() WHERE b.grant_id=$5 AND b.actor_id=$6 AND a.status='active' AND b.expires_at>clock_timestamp() AND s.expires_at>clock_timestamp() ON CONFLICT(model,id) DO UPDATE SET payload=EXCLUDED.payload,expires_at=LEAST(axxium_oidc_records.expires_at,EXCLUDED.expires_at)"
                                   [model id (js/JSON.stringify payload) expires-in
                                    (.-grantId payload) (.-accountId payload)]))]
               (when-not (= 1 (:row-count result))
                 (throw (new (.-InvalidGrant errors) "Root grant is no longer current"))))
             (await (pg/query! pool
                      "INSERT INTO axxium_oidc_records(model,id,payload,expires_at) VALUES($1,$2,$3::jsonb,clock_timestamp()+$4*interval '1 second') ON CONFLICT(model,id) DO UPDATE SET payload=EXCLUDED.payload,expires_at=CASE WHEN EXCLUDED.model='DeviceCode' THEN LEAST(axxium_oidc_records.expires_at,EXCLUDED.expires_at) ELSE EXCLUDED.expires_at END"
                      [model id (js/JSON.stringify payload) expires-in]))))
         :find
         (fn ^:async find-record [id]
           (require-key! id)
           (let [{:keys [poll? authenticated-client-id]} (polling-context!)]
             (if (and (= model "DeviceCode") poll?)
               (await (find-polled-device! pool id authenticated-client-id))
               (decode-record
                 (await (pg/query-one! pool
                          "SELECT payload,consumed_at FROM axxium_oidc_records WHERE model=$1 AND id=$2 AND expires_at>clock_timestamp()"
                          [model id]))))))
         :findByUserCode
         (fn ^:async find-user-code [code]
           (require-key! code)
           (decode-record
             (await (pg/query-one! pool
                      "SELECT payload,consumed_at FROM axxium_oidc_records WHERE model=$1 AND payload->>'userCode'=$2 AND expires_at>clock_timestamp()"
                      [model code]))))
         :findByUid
         (fn ^:async find-uid [uid]
           (require-key! uid)
           (decode-record
             (await (pg/query-one! pool
                      "SELECT payload,consumed_at FROM axxium_oidc_records WHERE model=$1 AND payload->>'uid'=$2 AND expires_at>clock_timestamp()"
                      [model uid]))))
         :destroy
         (fn ^:async destroy-record [id]
           (require-key! id)
           (await (pg/query! pool "DELETE FROM axxium_oidc_records WHERE model=$1 AND id=$2" [model id])))
         :revokeByGrantId
         (fn ^:async revoke-grant [grant-id]
           (require-key! grant-id)
           (await (pg/query! pool "DELETE FROM axxium_oidc_records WHERE payload->>'grantId'=$1" [grant-id])))
         :consume
         (fn ^:async consume-record [id]
           (require-key! id)
           (let [result (await (pg/query! pool
                                "UPDATE axxium_oidc_records SET consumed_at=floor(extract(epoch from clock_timestamp())) WHERE model=$1 AND id=$2 AND consumed_at IS NULL AND expires_at>clock_timestamp()"
                                [model id]))]
             (when-not (= 1 (:row-count result))
               (throw (new (.-InvalidGrant errors) "Grant expired, missing or already consumed")))))})))
