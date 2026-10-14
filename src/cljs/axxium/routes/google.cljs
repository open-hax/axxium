(ns axxium.routes.google
  "Google sign-in binds a verified OIDC subject to one Axxium human actor."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.extern.google :as google]
            [axxium.extern.jose :as jose]
            [axxium.extern.http :as http]
            [clojure.string :as str]))

(def ^:private state-cookie "axxium_google_state")

(defn- state-keys []
  {:secret (cfg/get-in-config [:jwt/secret])
   :issuer (cfg/get-in-config [:jwt/issuer])})

(defn- ^:async start!
  "Begin Google sign-in with stateless, signed browser state (no server-side store)."
  [_req reply]
  (if-not (google/configured?)
    (http/send! reply 503 {:error "Google redirect URI is not registered for this Axxium origin"})
    (let [state (google/random-secret)
          nonce (google/random-secret)
          verifier (google/random-secret)
          sealed (await (jose/sign-google-state!
                         {:state state :nonce nonce :verifier verifier} (state-keys)))]
      (http/set-state-cookie! reply state-cookie sealed
                              (str/starts-with?
                               (cfg/get-in-config [:axxium/public-base-url]) "https://"))
      (http/redirect! reply (google/authorize-url state nonce verifier)))))

(defn- ^:async bound-actor!
  "Return the subject-bound actor, atomically creating or recovering the winning binding."
  [{:keys [subject email display-name]}]
  (if-let [actor (await (db/query-one-sql
                         (db/q-select-actor-by-provider-subject "google" subject)))]
    actor
    (let [email (str/lower-case (str/trim email))]
      (when (await (db/query-one-sql (db/q-select-actor-by-email {:email email})))
        (throw (ex-info "Email is already bound to another Axxium actor" {:status 409})))
      (let [entity-id (str "entity_" (random-uuid))
            actor-id (str "actor_" (random-uuid))
            admin-email (-> (cfg/get-in-config [:oauth/bootstrap-admin-email])
                            str/trim str/lower-case)
            roles (if (and (seq admin-email) (= email admin-email))
                    [:axxium/system-admin] [:axxium/user])]
        (try
          (await
           (db/with-transaction!
            (fn ^:async bind-google-rows [query!]
              (await (query! (db/q-insert-entity {:id entity-id :kind "human"
                                                  :email email :display-name display-name})))
              (await (query! (db/q-insert-actor {:id actor-id :entity-id entity-id
                                                 :email email :display-name display-name
                                                 :capabilities [:axxium/login :axxium/read]
                                                 :roles roles :status "active"})))
              (await (query! (db/q-insert-provider-binding "google" subject actor-id)))
              (first (await (query! (db/q-select-actor-by-id {:id actor-id})))))))
          (catch :default error
            ;; Unique email or provider-binding: the winner finished first. Reload
            ;; by provider subject (atomic txn wrote binding with the actor).
            (if (or (db/provider-binding-conflict? error)
                    (db/actor-email-conflict? error))
              (or (await (db/query-one-sql
                         (db/q-select-actor-by-provider-subject "google" subject)))
                  (throw error))
              (throw error))))))))

(defn- ^:async callback! [req reply]
  (try
    (let [state (http/query-param req "state")
          code (http/query-param req "code")
          sealed (http/cookie req state-cookie)
          opened (when (seq sealed)
                   (await (jose/verify-google-state! sealed (state-keys))))
          entry (when (and (seq state) (= state (:state opened))) opened)]
      (http/clear-state-cookie! reply state-cookie)
      (if (and entry (seq code))
        (let [profile (await (google/verified-profile!
                              code (:verifier entry) (:nonce entry)))
              actor (await (bound-actor! profile))
              {:keys [token]} (await (session/create-session! actor))]
          (session/set-session-cookie reply token)
          (http/redirect! reply "/portal/index.html"))
        (http/send! reply 400 {:error "Invalid or expired Google sign-in state"})))
    (catch :default err
      (let [{:keys [stage provider-status provider-error reason]} (ex-data err)]
        (println "Google sign-in failed at" (or stage :actor-or-session)
                 "provider-status" provider-status
                 "provider-error" provider-error
                 "reason" reason))
      (http/send! reply (or (:status (ex-data err)) 502)
                  {:error (if (= 409 (:status (ex-data err)))
                            "Email is already bound to another Axxium actor"
                            "Google sign-in failed")}))))

(defn register-google-routes! [app]
  (http/get! app "/api/auth/google/start"
             (http/with-canonical-origin
              (cfg/get-in-config [:axxium/public-base-url])
              "/api/auth/google/start" start!))
  (http/get! app "/api/auth/google/callback" callback!))
