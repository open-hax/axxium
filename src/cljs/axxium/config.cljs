(ns axxium.config
  "Axxium runtime configuration from environment."
  (:require [axxium.domain.token :as token-domain]
            [axxium.extern.env :as environment]
            [axxium.law.atproto :as atproto-law]
            [axxium.law.signing :as signing-law]
            [axxium.law.token :as token-law]
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

(def config
  (let [instance (load-instance)]
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
