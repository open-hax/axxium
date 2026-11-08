(ns axxium.auth.session
  "Session management for Axxium.
   Cookie-based sessions for browser clients,
   JWT bearer tokens for API clients."
  (:require [axxium.auth.token :as token]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.domain.token :as token-domain]
            [axxium.extern.agent-credentials :as secrets]
            [axxium.extern.http :as http]
            [axxium.law.actor :as actor-law]
            [axxium.law.principal :as principal]
            [clojure.string :as str]))

(def COOKIE-NAME (cfg/get-in-config [:session/cookie-name]))

(defn- ^:async issue-session!
  [actor issue-token!]
  (let [cred-id (str (random-uuid))
        value (await (issue-token! actor cred-id))
        expires-at (secrets/expires-at (cfg/get-in-config [:jwt/expiry-hours]))]
    (await (db/query-sql
            (db/q-insert-session {:id cred-id
                                  :actor-id (:id actor)
                                  :token-hash (secrets/token-hash value)
                                  :expires-at expires-at})))
    {:token value :actor actor :cred-id cred-id}))

(defn create-session!
  "Create a live session row and its actor JWT."
  [actor]
  (issue-session! actor token/create-token))

(defn create-atproto-session!
  "Create a session that records the verified AT Protocol sign-in DID."
  [actor did]
  (issue-session! actor #(token/create-atproto-token! %1 %2 did)))

(defn ^:async atproto-session-proof
  "Return a proof from the signed callback DID without retaining the bearer."
  [value]
  (when-not (str/blank? value)
    (when-let [claims (try
                        (await (token/verify-token value))
                        (catch :default _ nil))]
      (when-let [p (principal/from-atproto-session (:atproto_did claims))]
        {:principal p :actor-id (:sub claims) :cred-id (:cred_id claims)
         :token-hash (secrets/token-hash value)}))))

(defn ^:async active-atproto-session?
  "Check the live session and current provider binding in one database read."
  [{:keys [principal actor-id cred-id token-hash]}]
  (some? (await (db/query-one-sql
                 (db/q-select-actor-by-atproto-session
                  actor-id token-hash cred-id (:auth/did principal))))))

(defn ^:async verify-session
  "Verify a session token. Returns promise of actor or nil.
   JWT verification failures return nil (401 upstream). Database errors
   propagate on both the JWT and axx_ branches so a DB outage surfaces as 500."
  [token]
  (cond
    (str/blank? token) nil
    (str/starts-with? token "axx_")
    (await (db/query-one-sql
            (db/q-select-active-agent-credential (secrets/token-hash token))))
    :else
    (when-let [claims (try
                        (await (token/verify-token token))
                        (catch :default _ nil))]
      (await (db/query-one-sql
              (db/q-select-actor-by-session
               (:sub claims) (secrets/token-hash token) (:cred_id claims)))))))

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

(defn- acting-credential-id
  "The session or agent credential id on a verified row, when it is a credential id."
  [actor]
  (let [id (:credential_id actor)
        cred-id (cond
                  (string? id) id
                  (uuid? id) (str id))]
    (when (actor-law/valid-credential-id? cred-id)
      cred-id)))

(defn ^:async resolve-auth-context
  "Resolve auth context from request. Returns a promise of a context map or nil.
   :auth/cred-id is the session or agent credential that acted. A verified row
   whose credential id is missing or not a credential id is refused."
  [req]
  (when-let [actor (await (verify-session (extract-auth-token req)))]
    (when-let [cred-id (acting-credential-id actor)]
      (token-domain/auth-context actor cred-id))))

(defn ^:async active-human-session?
  "Recheck the exact credential from a verified context at an authority boundary."
  [req {:auth/keys [actor-id cred-id]}]
  (let [value (extract-auth-token req)]
    (when (and (seq value)
               (not (str/starts-with? value "axx_"))
               (actor-law/valid-credential-id? cred-id))
      (some? (await (db/query-one-sql
                     (db/q-select-active-human-session
                      actor-id (secrets/token-hash value) cred-id)))))))
