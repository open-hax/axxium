(ns axxium.config
  "Operator-supplied runtime configuration; secrets are never returned by public endpoints."
  (:require [axxium.extern.runtime :as runtime]
            [clojure.string :as str]))

(defn- positive-int [name fallback]
  (let [n (parse-long (runtime/env name (str fallback)))]
    (when-not (and n (pos? n)) (throw (ex-info "Invalid positive runtime setting" {:setting name}))) n))

(def config
  {:axxium/port (positive-int "AXXIUM_PORT" 8787)
   :axxium/host (runtime/env "AXXIUM_HOST" "127.0.0.1")
   :axxium/public-base-url (runtime/env "AXXIUM_PUBLIC_BASE_URL" "http://localhost:8787")
   :db/host (runtime/env "DB_HOST" "localhost")
   :db/port (positive-int "DB_PORT" 5432)
   :db/name (runtime/env "DB_NAME" "axxium")
   :db/user (runtime/env "DB_USER" "axxium")
   :db/password (runtime/env "DB_PASSWORD" "")
   :jwt/secret (runtime/env "JWT_SECRET" "")
   :jwt/issuer (runtime/env "JWT_ISSUER" "axxium")
   :jwt/audience (runtime/env "JWT_AUDIENCE" "promethean")
   :jwt/expiry-hours (positive-int "JWT_EXPIRY_HOURS" 24)
   :session/cookie-name (runtime/env "SESSION_COOKIE_NAME" "axxium_session")
   :session/cookie-secure (= "true" (str/lower-case (runtime/env "SESSION_COOKIE_SECURE" "false")))
   :session/cookie-same-site "lax"
   :password/salt-rounds (positive-int "BCRYPT_SALT_ROUNDS" 12)
   :identity/private-key-file (runtime/env "AXXIUM_IDENTITY_KEY_FILE" "")
   :identity/trust-file (runtime/env "AXXIUM_IDENTITY_TRUST_FILE" "")})

(defn get-in-config "Read runtime configuration by path." [ks] (get-in config ks))
(defn validate! "Reject missing session signing secrets before serving traffic." []
  (when (< (count (:jwt/secret config)) 32)
    (throw (ex-info "JWT_SECRET must contain at least 32 characters" {}))))
