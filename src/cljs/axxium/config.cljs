(ns axxium.config
  "Axxium runtime configuration from environment."
  (:require [axxium.domain.token :as token-domain]
            [axxium.extern.env :as environment]
            [axxium.law.actor :as actor-law]
            [axxium.law.atproto :as atproto-law]
            [axxium.law.did :as did-law]
            [axxium.law.signing :as signing-law]
            [axxium.law.token :as token-law]
            [clio.shape.edn :as edn]
            [clojure.string :as str]))

(defn- env
  ([key] (env key nil))
  ([key default]
   (or (environment/get-value key)
       default)))

(defn- env-int [key default]
  (let [v (env key)]
    (if v (environment/parse-int v) default)))

(defn- env-bool [key default]
  (let [v (env key)]
    (if v (= "true" (str/lower-case v)) default)))

(defn parse-schema-statement-timeout-ms
  "Admit a bounded per-statement schema timeout in milliseconds."
  [value]
  (when-not (and (string? value) (re-matches #"[0-9]{1,6}" value))
    (throw (ex-info "Invalid AXXIUM_SCHEMA_STATEMENT_TIMEOUT_MS" {})))
  (let [timeout-ms (environment/parse-int value)]
    (when-not (<= 1000 timeout-ms 300000)
      (throw (ex-info "AXXIUM_SCHEMA_STATEMENT_TIMEOUT_MS must be 1000..300000" {})))
    timeout-ms))

(defn schema-statement-timeout-ms []
  (parse-schema-statement-timeout-ms (env "AXXIUM_SCHEMA_STATEMENT_TIMEOUT_MS" "20000")))

(defn- present
  "Environment values that are missing or blank are absent."
  [value]
  (when (and (string? value) (not (str/blank? value)))
    value))

(defn- load-instance
  "Per-instance (iss, service, env). aud is service+env and is not chosen separately.
   AXXIUM_ISS overrides JWT_ISSUER. A set JWT_AUDIENCE must equal the derived audience."
  []
  (let [iss (or (present (env "AXXIUM_ISS"))
                (present (env "JWT_ISSUER"))
                "axxium")
        service (or (present (env "AXXIUM_SERVICE")) "promethean")
        environment (or (present (env "AXXIUM_ENV"))
                        (present (env "NODE_ENV"))
                        "development")
        instance {:iss iss :service service :env environment}
        configured-aud (present (env "JWT_AUDIENCE"))]
    (when-not (token-law/admitted-instance? instance)
      (throw (ex-info "Instance iss, service, and env are outside the token law"
                      {:contract :axxium.law.token/admitted-instance?})))
    (when-not (token-law/configured-audience-admitted? configured-aud service environment)
      (throw (ex-info "JWT_AUDIENCE must equal the service+env audience"
                      {:contract :axxium.law.token/configured-audience-admitted?
                       :expected (token-domain/audience service environment)})))
    instance))

(defn- signing-kid
  "Optional AXXIUM_SIGNING_KID. A value that is not a kid is a startup failure
   and the value is not copied into the failure."
  []
  (when-let [kid (present (env "AXXIUM_SIGNING_KID"))]
    (if (signing-law/kid? kid)
      kid
      (throw (ex-info "AXXIUM_SIGNING_KID is outside the signing law"
                      {:reason :invalid-kid})))))

(defn trust-proxy-option
  "Fastify trustProxy from AXXIUM_TRUST_PROXY, admitted by
   `axxium.law.atproto/trust-proxy-admission`; a refusal is a configuration
   error (bare true and hop counts are not supported)."
  [value]
  (let [[decision setting] (atproto-law/trust-proxy-admission value)]
    (case decision
      :admit setting
      :refuse (throw (ex-info "AXXIUM_TRUST_PROXY must name trusted proxy addresses/CIDRs; bare true and hop counts are not supported"
                              {:axxium/reason (keyword "axxium.config" (name setting))
                               :law/reason setting})))))

(defn root-operator-binding
  "Admit both host-local settings together, or refuse startup."
  [actor-id root-did]
  (cond
    (and (nil? actor-id) (nil? root-did)) nil
    (and (string? actor-id)
         (str/starts-with? actor-id "actor_")
         (actor-law/valid-credential-id? (subs actor-id 6))
         (did-law/did? root-did))
    {:actor-id actor-id :root-did root-did}
    :else
    (throw (ex-info "AXXIUM_IDENTITY_ROOT_OPERATOR_ACTOR_ID and AXXIUM_IDENTITY_ROOT_DID must specify a canonical actor ID and a DID"
                    {:reason :invalid-identity-root-operator-binding}))))

(defn mailbox-pilot-allowed-dids
  "Admit exactly two distinct AT Protocol DIDs including the webhook recipient."
  [value webhook-actor]
  (when (some? value)
    (let [dids (str/split value #"," -1)
          allowed (set dids)]
      (when-not (and (= 2 (count dids) (count allowed))
                     (every? #(and (atproto-law/did? %) (did-law/did? %)) dids)
                     (contains? allowed webhook-actor))
        (throw (ex-info "AXXIUM_MAILBOX_PILOT_ALLOWED_DIDS must contain two distinct AT Protocol DIDs including AXXIUM_MAILBOX_WEBHOOK_ACTOR_DID"
                        {:reason :invalid-mailbox-pilot-allowed-dids})))
      allowed)))

(defn mailbox-actor-bindings
  "Admit a one-to-one operator-supplied vector of [AT DID, actor DID] pairs."
  [value]
  (when (some? value)
    (let [pairs (when (and (string? value) (<= 1 (count value) 65536))
                  (try (edn/read-one value) (catch :default _ nil)))]
      (when-not (and (vector? pairs) (seq pairs)
                     (every? (fn [pair]
                               (and (vector? pair) (= 2 (count pair))
                                    (atproto-law/did? (first pair))
                                    (did-law/did? (first pair))
                                    (did-law/web? (second pair))))
                             pairs)
                     (= (count pairs) (count (set (map first pairs)))
                        (count (set (map second pairs)))))
        (throw (ex-info "AXXIUM_MAILBOX_ACTOR_BINDINGS requires unique AT Protocol and actor DID pairs"
                        {:reason :invalid-mailbox-actor-bindings})))
      (into {} pairs))))

(def config
  (let [instance (load-instance)
        webhook-actor (present (env "AXXIUM_MAILBOX_WEBHOOK_ACTOR_DID"))]
    {:axxium/env (or (env "NODE_ENV") "development")
     :axxium/port (env-int "AXXIUM_PORT" 8787)
     :axxium/host (env "AXXIUM_HOST" "0.0.0.0")
     :axxium/public-base-url (env "AXXIUM_PUBLIC_BASE_URL" "http://127.0.0.1:8787")
     ;; Needed behind a reverse proxy so req.ip (start rate limit) is the client.
     :http/trust-proxy (trust-proxy-option (env "AXXIUM_TRUST_PROXY"))

     ;; Database
     :db/host (env "DB_HOST" "localhost")
     :db/port (env-int "DB_PORT" 5432)
     :db/name (env "DB_NAME" "axxium")
     :db/user (env "DB_USER" "axxium")
     :db/password (env "DB_PASSWORD" "")

     ;; Instance identity. aud is service+env; iss names this instance.
     :instance/iss (:iss instance)
     :instance/service (:service instance)
     :instance/env (:env instance)
     :signing/key-file (present (env "AXXIUM_SIGNING_KEY_FILE"))
     :signing/kid (signing-kid)
     :jwt/issuer (:iss instance)
     :jwt/audience (token-domain/audience (:service instance) (:env instance))
     :jwt/expiry-hours (env-int "JWT_EXPIRY_HOURS" 168) ;; 7 days

     ;; OAuth
     :oauth/github-client-id (env "GITHUB_OAUTH_CLIENT_ID" "")
     :oauth/github-client-secret (env "GITHUB_OAUTH_CLIENT_SECRET" "")
     :oauth/github-enabled (env-bool "GITHUB_OAUTH_ENABLED" false)
     :oauth/google-client-file (env "GOOGLE_OAUTH_CLIENT_FILE" "")
     ;; Google Drive resource: same Cloud OAuth client, drive.file scope.
     ;; The refresh-token encryption key is a 32-byte base64 file, never committed.
     :drive/token-key-file (env "AXXIUM_DRIVE_TOKEN_KEY_FILE" "")
     ;; Optional explicit client; empty falls back to GOOGLE_OAUTH_CLIENT_FILE.
     :drive/client-id (env "GOOGLE_DRIVE_CLIENT_ID" "")
     :drive/client-secret (env "GOOGLE_DRIVE_CLIENT_SECRET" "")
     ;; Append-only EDN ledger holding Drive credential and file resources.
     :drive/ledger-file (env "AXXIUM_DRIVE_LEDGER_FILE" "")
     ;; Bot identity tree + per-actor GitHub Apps. Key: 32-byte base64 file.
     :github-identity/token-key-file (env "AXXIUM_GITHUB_IDENTITY_KEY_FILE" "")
     :github-identity/ledger-file (env "AXXIUM_GITHUB_IDENTITY_LEDGER_FILE" "")
     ;; Directory imported App PEMs may be read from in place (never copied).
     :github-identity/key-dir (env "AXXIUM_GITHUB_IDENTITY_KEY_DIR" "")
     :identity/seed-file (env "AXXIUM_IDENTITY_SEED_FILE" "")
     :mailbox/ledger-file (present (env "AXXIUM_MAILBOX_LEDGER_FILE"))
     :mailbox/subscriptions-enabled? (env-bool "AXXIUM_MAILBOX_SUBSCRIPTIONS_ENABLED" true)
     :mailbox/stream-enabled? (env-bool "AXXIUM_MAILBOX_STREAM_ENABLED" false)
     :mailbox/webhook-key-file (present (env "AXXIUM_MAILBOX_WEBHOOK_KEY_FILE"))
     :mailbox/webhook-actor-did webhook-actor
     :mailbox/pilot-allowed-dids (mailbox-pilot-allowed-dids (env "AXXIUM_MAILBOX_PILOT_ALLOWED_DIDS") webhook-actor)
     ;; Bootstrap seed only; the identity ledger holds the bindings.
     :mailbox/actor-bindings (mailbox-actor-bindings (env "AXXIUM_MAILBOX_ACTOR_BINDINGS"))
     ;; Owner-directory routing over ledger bindings, with or without a seed.
     :mailbox/directory? (env-bool "AXXIUM_MAILBOX_DIRECTORY" false)
     :identity/root-operator-binding
     (root-operator-binding (env "AXXIUM_IDENTITY_ROOT_OPERATOR_ACTOR_ID")
                            (env "AXXIUM_IDENTITY_ROOT_DID"))
     :oauth/bootstrap-admin-email (env "AXXIUM_BOOTSTRAP_ADMIN_EMAIL" "")

     ;; Session
     :session/cookie-name (env "SESSION_COOKIE_NAME" "axxium_session")
     :session/cookie-secure (env-bool "SESSION_COOKIE_SECURE" false)
     :session/cookie-same-site (env "SESSION_COOKIE_SAME_SITE" "lax")

     ;; Password hashing
     :password/salt-rounds (env-int "BCRYPT_SALT_ROUNDS" 12)}))

(defn instance
  "The admitted (iss, service, env) triple this process signs and verifies as."
  []
  {:iss (get config :instance/iss)
   :service (get config :instance/service)
   :env (get config :instance/env)})

(defn get-in-config [ks]
  (get-in config ks))

(defn db-url []
  (let [{:keys [db/host db/port db/name db/user db/password]} config]
    (str "postgresql://" user 
         (when (seq password) (str ":" password))
         "@" host ":" port "/" name)))
