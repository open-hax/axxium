(ns axxium.routes.agents
  "Human administrators create agent actors and issue revocable, expiring secrets."
  (:require [axxium.db :as db]
            [axxium.extern.agent-credentials :as secrets]
            [axxium.extern.http :as http]
            [axxium.law.actor :as law]
            [axxium.routes.auth-context :as auth]
            [clojure.string :as str]))

(defn- ^:async agent-actor
  "Return the actor only when its linked entity is an agent."
  [id]
  (when-let [actor (await (db/query-one-sql (db/q-select-actor-by-id {:id id})))]
    (let [entity (await (db/query-one-sql (db/q-select-entity-for-actor id)))]
      (when (law/agent? entity) actor))))

(defn- ^:async create-agent!
  "Atomically create paired agent records after administrator admission."
  [req reply _context]
  (try
    (let [{:keys [display-name]} (http/body req)
          name (some-> display-name str/trim)]
      (if (or (str/blank? name) (> (count name) 200))
        (http/send! reply 400 {:error "display-name must be 1–200 characters"})
        (let [entity-id (str "entity_" (random-uuid))
              actor-id (str "actor_" (random-uuid))]
          (await
           (db/with-transaction!
            (fn ^:async create-agent-rows [query!]
              (await (query! (db/q-insert-entity
                              {:id entity-id :kind "agent" :display-name name})))
              (await (query! (db/q-insert-actor
                              {:id actor-id :entity-id entity-id :display-name name
                               :capabilities [] :roles [] :status "active"}))))))
          (http/send! reply 201 {:actor {:id actor-id :entity_id entity-id
                                        :kind "agent" :display_name name}}))))
    (catch :default err
      (println "Agent creation failed:" (http/error-message err))
      (http/send! reply 500 {:error "Could not create agent"}))))

(defn- ^:async issue-credential!
  "Issue an expiring credential for an admitted administrator and valid agent."
  [req reply admin]
  (try
    (let [actor-id (http/param req "id")
          actor (await (agent-actor actor-id))
          body (http/body req)
          request {:label (:label body) :expires-in-hours (:expires-in-hours body)}]
      (cond
        (nil? actor) (http/send! reply 404 {:error "Agent not found"})
        (not (law/valid-credential-request? request))
        (http/send! reply 400 {:error "label and expires-in-hours (1–2160) required"})
        :else
        (let [token (secrets/issue-token)
              id (str (random-uuid))
              expires-at (secrets/expires-at (:expires-in-hours request))]
          (await (db/query-sql
                  (db/q-insert-agent-credential
                   {:id id :actor-id actor-id :token-hash (secrets/token-hash token)
                    :expires-at expires-at :label (:label request)
                    :issued-by (:auth/actor-id admin)})))
          (http/send-secret! reply 201
                             {:credential {:id id :actor_id actor-id :label (:label request)
                                           :expires_at expires-at}
                              :token token}))))
    (catch :default err
      (println "Credential issuance failed:" (http/error-message err))
      (http/send! reply 500 {:error "Could not issue credential"}))))

(defn- ^:async list-credentials!
  "List agent credentials with controlled database-error responses."
  [req reply _context]
  (try
    (let [actor-id (http/param req "id")]
      (if (await (agent-actor actor-id))
        (http/send! reply 200
                    {:credentials (await (db/query-all-sql
                                          (db/q-select-agent-credentials actor-id)))})
        (http/send! reply 404 {:error "Agent not found"})))
    (catch :default err
      (println "Credential listing failed:" (http/error-message err))
      (http/send! reply 500 {:error "Could not list credentials"}))))

(defn- ^:async revoke-credential!
  "Revoke an agent-owned credential while preserving not-found responses."
  [req reply _context]
  (try
    (let [actor-id (http/param req "id")
          credential-id (http/param req "credentialId")]
      (if (and (await (agent-actor actor-id))
               (seq (await (db/query-sql
                            (db/q-revoke-agent-credential credential-id actor-id)))))
        (http/send! reply 200 {:ok true})
        (http/send! reply 404 {:error "Credential not found"})))
    (catch :default err
      (println "Credential revocation failed:" (http/error-message err))
      (http/send! reply 500 {:error "Could not revoke credential"}))))

(defn register-agent-routes!
  "Mount all agent and credential endpoints behind shared administrator admission."
  [app]
  (http/post! app "/api/actors/agents" (auth/with-admin create-agent!))
  (http/post! app "/api/actors/:id/credentials" (auth/with-admin issue-credential!))
  (http/get! app "/api/actors/:id/credentials" (auth/with-admin list-credentials!))
  (http/delete! app "/api/actors/:id/credentials/:credentialId" (auth/with-admin revoke-credential!)))
