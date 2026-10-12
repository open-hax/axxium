(ns axxium.auth.session
  "Session management for Axxium.
   Cookie-based sessions for browser clients,
   JWT bearer tokens for API clients."
  (:require [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.auth.token :as token]
            [axxium.extern.agent-credentials :as secrets]
            [axxium.extern.http :as http]
            [clojure.string :as str]))

(def COOKIE-NAME (cfg/get-in-config [:session/cookie-name]))

(defn ^:async create-session!
  "Create a session for an actor. Returns {:token token :actor actor}."
  [actor]
  (let [value (await (token/create-token actor))
        expires-at (secrets/expires-at (cfg/get-in-config [:jwt/expiry-hours]))]
    (await (db/query-sql
            (db/q-insert-session {:actor-id (:id actor)
                                  :token-hash (secrets/token-hash value)
                                  :expires-at expires-at})))
    {:token value :actor actor}))

(defn ^:async verify-session
  "Verify a session token. Returns promise of actor or nil."
  [token]
  (cond
    (str/blank? token) nil
    (str/starts-with? token "axx_")
    (await (db/query-one-sql
            (db/q-select-active-agent-credential (secrets/token-hash token))))
    :else
    (try
      (let [claims (await (token/verify-token token))]
        (await (db/query-one-sql
                (db/q-select-actor-by-session
                 (:sub claims) (secrets/token-hash token)))))
      (catch :default _ nil))))

(defn delete-session!
  "Delete a session by token."
  [token]
  (db/query-sql (db/q-delete-session-by-hash (secrets/token-hash token))))

(defn set-session-cookie
  "Set the session cookie on a Fastify reply."
  [reply token]
  (http/set-session-cookie!
   reply COOKIE-NAME token
   {:secure (cfg/get-in-config [:session/cookie-secure])
    :same-site (cfg/get-in-config [:session/cookie-same-site])
    :max-age (* (cfg/get-in-config [:jwt/expiry-hours]) 3600)}))

(defn clear-session-cookie
  "Clear the session cookie."
  [reply]
  (http/clear-session-cookie! reply COOKIE-NAME))

(defn extract-auth-token
  "Extract bearer token from request headers or cookie."
  [req]
  (let [auth-header (str (or (http/authorization req) ""))
        cookie-token (http/cookie req COOKIE-NAME)]
    (or
     (when (str/starts-with? (str/lower-case auth-header) "bearer ")
       (str/trim (subs auth-header 7)))
     cookie-token)))

(defn ^:async resolve-auth-context
  "Resolve auth context from request. Returns promise of context map or nil."
  [req]
  (when-let [actor (await (verify-session (extract-auth-token req)))]
    {:auth/actor-id (:id actor)
     :auth/entity-id (:entity_id actor)
     :auth/email (:email actor)
     :auth/capabilities (:capabilities actor)
     :auth/roles (:roles actor)}))
