(ns axxium.config
  "Axxium runtime configuration from environment."
  (:require [clojure.string :as str]
            [axxium.extern.env :as environment]))

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

(defn trust-proxy-option
  "Fastify trustProxy from AXXIUM_TRUST_PROXY: unset/empty keeps the default
   (no proxy trust), \"false\" is no proxy trust, \"true\" trusts every hop,
   and anything else is passed through as trusted addresses/CIDRs. A bare hop
   count is refused: Fastify 5 trusts no proxy for numeric trustProxy, which
   would silently key every client by the proxy address."
  [value]
  (let [v (some-> value str/trim)]
    (cond
      (or (nil? v) (= "" v)) nil
      (= "false" (str/lower-case v)) false
      (= "true" (str/lower-case v)) true
      (re-matches #"\d+" v)
      (throw (ex-info "AXXIUM_TRUST_PROXY hop counts are not supported; set the trusted proxy addresses/CIDRs"
                      {:axxium/reason :axxium.config/trust-proxy-hop-count :value v}))
      :else v)))

(def config
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
   
   ;; JWT
   :jwt/secret (env "JWT_SECRET" "axxium-dev-secret-change-me")
   :jwt/issuer (env "JWT_ISSUER" "axxium")
   :jwt/audience (env "JWT_AUDIENCE" "promethean")
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
   :password/salt-rounds (env-int "BCRYPT_SALT_ROUNDS" 12)})

(defn get-in-config [ks]
  (get-in config ks))

(defn db-url []
  (let [{:keys [db/host db/port db/name db/user db/password]} config]
    (str "postgresql://" user 
         (when (seq password) (str ":" password))
         "@" host ":" port "/" name)))
