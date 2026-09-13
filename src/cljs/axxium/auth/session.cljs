(ns axxium.auth.session
  "Persisted local sessions; no live dependency on the original identity issuer."
  (:require [axxium.auth.token :as token]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.extern.runtime :as runtime]
            [clojure.string :as str]))
(defn ^:async create-session! "Create a distinct expiring session for a local actor." [actor]
  (let [value (await (token/create-token actor))]
    (await (db/query-sql (db/q-insert-session
             {:actor-id (:id actor) :token-hash (runtime/sha256 value)
              :expires-at (runtime/expires-at (cfg/get-in-config [:jwt/expiry-hours]))})))
    value))
(defn ^:async verify-session "Require valid JWT, persisted unexpired session, and active actor." [value]
  (when-not (str/blank? value)
    (try
      (let [claims (await (token/verify-token value))]
        (await (db/query-one-sql (db/q-select-actor-by-session (:sub claims) (runtime/sha256 value)))))
      (catch :default _ nil))))
(defn delete-session! "Revoke only the presented local session." [value]
  (db/query-sql (db/q-delete-session-by-hash (runtime/sha256 value))))
(defn extract-auth-token "Read bearer or same-host HttpOnly cookie from an HTTP boundary map." [request]
  (let [header (get-in request [:headers :authorization])]
    (if (and (string? header) (str/starts-with? (str/lower-case header) "bearer "))
      (str/trim (subs header 7))
      (get-in request [:cookies (keyword (cfg/get-in-config [:session/cookie-name]))]))))
(defn ^:async require-actor! "Reject unauthenticated access before any account operation." [request]
  (or (await (verify-session (extract-auth-token request)))
      (throw (ex-info "Authentication required" {:status 401 :code "unauthorized"}))))
