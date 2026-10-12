(ns axxium.routes.atproto-oauth
  "A verified AT Protocol OAuth DID binds to one human Axxium actor."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.extern.agent-credentials :as secrets]
            [axxium.extern.atproto-oauth :as oauth]
            [axxium.extern.http :as http]
            [axxium.law.atproto :as atproto-law]
            [clojure.string :as str]))

(def ^:private state-cookie "axxium_atproto_state")
(def ^:private callback-path "/api/auth/atproto/callback")

(defn- ^:async bound-actor!
  "Return the DID-bound actor, atomically creating or recovering the winning binding."
  [did]
  (let [selection (db/q-select-actor-by-provider-subject "atproto" did)]
    (if-let [actor (await (db/query-one-sql selection))]
      actor
      (let [entity-id (str "entity_" (random-uuid))
            actor-id (str "actor_" (random-uuid))]
        (try
          (await
           (db/with-transaction!
            (fn ^:async bind-identity-rows [query!]
              (await (query! (db/q-insert-entity
                              {:id entity-id :kind "human" :display-name did})))
              (await (query! (db/q-insert-actor
                              {:id actor-id :entity-id entity-id :display-name did
                               :capabilities [:axxium/login :axxium/read]
                               :roles [:axxium/user] :status "active"})))
              (await (query! (db/q-insert-provider-binding "atproto" did actor-id)))
              (first (await (query! (db/q-select-actor-by-id {:id actor-id})))))))
          (catch :default error
            (if (db/provider-binding-conflict? error)
              (or (await (db/query-one-sql selection)) (throw error))
              (throw error))))))))

(defn- ^:async link-actor! [req did expected-actor-id]
  (let [context (await (session/resolve-auth-context req))
        actor-id (:auth/actor-id context)
        entity (when actor-id
                 (await (db/query-one-sql (db/q-select-entity-for-actor actor-id))))
        binding (await (db/query-one-sql
                        (db/q-select-actor-by-provider-subject "atproto" did)))]
    (cond
      (or (not= expected-actor-id actor-id)
          (not= "human" (:kind entity)))
      (throw (ex-info "A human Axxium session is required to link an AT identity" {:status 403}))

      (and binding (not= actor-id (:id binding)))
      (throw (ex-info "AT identity is already bound to another actor" {:status 409}))

      :else
      (do
        (when-not binding
          (await (db/query-sql
                  (db/q-insert-provider-binding "atproto" did actor-id))))
        (db/query-one-sql (db/q-select-actor-by-id {:id actor-id}))))))

(defn- metadata! [_req reply]
  (http/send! reply 200 (oauth/client-metadata)))

(defn- ^:async start! [req reply]
  (let [identity (some-> (http/query-param req "identity") str str/trim)
        link? (= "1" (http/query-param req "link"))
        context (when link? (await (session/resolve-auth-context req)))
        link-actor-id (:auth/actor-id context)
        entity (when link-actor-id
                 (await (db/query-one-sql
                         (db/q-select-entity-for-actor link-actor-id))))]
    (cond
      (not (atproto-law/identity? identity))
      (http/send! reply 400 {:error "A valid AT Protocol handle or DID is required"})

      (and link? (not= "human" (:kind entity)))
      (http/send! reply 403 {:error "A human Axxium session is required to link an AT identity"})

      :else
      (try
        (let [browser-state (str (when link? (str "link|" link-actor-id "|"))
                                 (secrets/issue-token))
              authorize-url (await (oauth/authorize! identity browser-state))]
          (http/set-state-cookie! reply state-cookie browser-state
                                  (str/starts-with?
                                   (cfg/get-in-config [:axxium/public-base-url]) "https://")
                                  callback-path)
          (http/redirect! reply authorize-url))
        (catch :default _
          (http/send! reply 502 {:error "AT Protocol sign-in could not start"}))))))

(defn- ^:async callback! [req reply]
  (let [browser-state (http/cookie req state-cookie)]
    (http/clear-state-cookie! reply state-cookie callback-path)
    (if-not (seq browser-state)
      (http/send! reply 400 {:error "Missing AT Protocol sign-in state"})
      (try
        (let [{:keys [did state session]} (await (oauth/callback! (http/request-url req)))
              _ (await (oauth/revoke! session))]
          (if (and (= browser-state state) (atproto-law/did? did))
            (let [actor (await (if (str/starts-with? state "link|")
                                 (link-actor! req did (second (str/split state #"\|")))
                                 (bound-actor! did)))
                  {:keys [token]} (await (session/create-session! actor))]
              (session/set-session-cookie reply token)
              (http/redirect! reply "/portal/index.html"))
            (http/send! reply 400 {:error "AT Protocol identity or browser state mismatch"})))
        (catch :default err
          (let [status (:status (ex-data err))]
            (http/send! reply (or status 502)
                        {:error (case status
                                  403 "A human Axxium session is required to link an AT identity"
                                  409 "AT identity is already bound to another actor"
                                  "AT Protocol sign-in failed")})))))))

(defn register-atproto-oauth-routes!
  "Mount AT Protocol OAuth endpoints with canonical-origin sign-in starts."
  [app]
  (http/get! app "/api/auth/atproto/client-metadata.json" metadata!)
  (http/get! app "/api/auth/atproto/start"
             (http/with-canonical-origin
              (cfg/get-in-config [:axxium/public-base-url])
              "/api/auth/atproto/start" start!))
  (http/get! app callback-path callback!))
