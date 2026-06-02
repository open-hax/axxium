(ns axxium.routes.auth
  "Authentication routes for Axxium.
   Provides login, signup, logout, and OAuth callbacks.
   Designed to be consumed by proxx, knoxx, and openplanner."
  (:require [clojure.string :as str]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.auth.session :as session]
            [axxium.schema :as schema]
            [axxium.extern.bcrypt :as bcrypt]))

(defn- body-map [req]
  (js->clj (or (aget req "body") #js {}) :keywordize-keys true))

(defn- http-error [status code message]
  (let [err (js/Error. message)]
    (set! (.-statusCode err) status)
    (set! (.-code err) code)
    err))

(defn- hash-password [password]
  (let [salt-rounds (cfg/get-in-config [:password/salt-rounds])]
    (bcrypt/hash password salt-rounds)))

(defn- verify-password [password hash]
  (bcrypt/compare password hash))

(defn- sanitize-actor [actor]
  (dissoc actor :password_hash))

(defn ^:async handle-signup [req reply]
  (try
    (let [body (body-map req)
          email (str/lower-case (str/trim (str (:email body))))
          password (str (:password body))
          display-name (str/trim (str (or (:display-name body) (:display_name body) email)))]
      (cond
        (str/blank? email)
        (.send (.code reply 400) (clj->js {:error "email is required"}))

        (str/blank? password)
        (.send (.code reply 400) (clj->js {:error "password is required"}))

        (< (count password) 8)
        (.send (.code reply 400) (clj->js {:error "password must be at least 8 characters"}))

        :else
        (let [existing (await (db/query-one-sql
                                (db/q-select-actor-by-email {:email email})))]
          (when existing
            (throw (http-error 409 "email_exists" "An account with this email already exists")))
          (let [password-hash (await (hash-password password))
                entity-id (str "entity_" (random-uuid))
                actor-id (str "actor_" (random-uuid))
                _ (await (db/query-sql (db/q-insert-entity {:id entity-id
                                                            :kind "human"
                                                            :email email
                                                            :display-name display-name})))
                _ (await (db/query-sql (db/q-insert-actor {:id actor-id
                                                            :entity-id entity-id
                                                            :email email
                                                            :display-name display-name
                                                            :password-hash password-hash
                                                            :capabilities [:axxium/login :axxium/read :axxium/write]
                                                            :roles [:axxium/user]
                                                            :status "active"})))
                actor (await (db/query-one-sql (db/q-select-actor-by-id {:id actor-id})))
                actor (js->clj actor :keywordize-keys true)
                {:keys [token]} (await (session/create-session! actor))]
            (session/set-session-cookie reply token)
            (.send reply (clj->js
                          {:ok true
                           :actor (sanitize-actor actor)
                           :token token}))))))
    (catch js/Error err
      (.send (.code reply (or (.-statusCode err) 500))
             (clj->js {:error (or (.-message err) "Signup failed")
                       :code (or (.-code err) "unknown")})))))

(defn ^:async handle-login [req reply]
  (try
    (let [body (body-map req)
          email (str/lower-case (str/trim (str (:email body))))
          password (str (:password body))]
      (if (or (str/blank? email) (str/blank? password))
        (.send (.code reply 400) (clj->js {:error "email and password are required"}))
        (let [actor (await (db/query-one-sql
                            (db/q-select-actor-by-email-active {:email email})))]
          (if-not actor
            (throw (http-error 401 "invalid_credentials" "Invalid email or password"))
            (let [actor (js->clj actor :keywordize-keys true)
                  valid? (await (verify-password password (:password_hash actor)))]
              (if-not valid?
                (throw (http-error 401 "invalid_credentials" "Invalid email or password"))
                (let [{:keys [token]} (await (session/create-session! actor))]
                  (session/set-session-cookie reply token)
                  (.send reply (clj->js
                                {:ok true
                                 :actor (sanitize-actor actor)
                                 :token token})))))))))))
     (catch js/Error err
       (.send (.code reply (or (.-statusCode err) 500))
              (clj->js {:error (or (.-message err) "Login failed")
                        :code (or (.-code err) "unknown")})))

(defn register-signup-route!
  "POST /api/auth/signup — Email/password registration."
  [app]
  (.post app "/api/auth/signup" handle-signup))

(defn register-login-route!
  "POST /api/auth/login — Email/password login."
  [app]
  (.post app "/api/auth/login" handle-login))

(defn register-logout-route!
  "POST /api/auth/logout — Clear session."
  [app]
  (.post app "/api/auth/logout"
         (fn [req reply]
           (let [token (session/extract-auth-token req)]
             (when token
               (session/delete-session! token))
             (session/clear-session-cookie reply)
             (.send reply (clj->js {:ok true}))))))

(defn register-me-route!
  "GET /api/auth/me — Get current actor."
  [app]
  (.get app "/api/auth/me"
        (fn [req reply]
          (-> (session/resolve-auth-context req)
              (.then (fn [ctx]
                       (if-not ctx
                         (.send (.code reply 401) (clj->js {:error "Unauthorized"}))
                         (-> (db/query-one-sql
                              (db/q-select-actor-by-id {:id (:auth/actor-id ctx)}))
                             (.then (fn [actor]
                                      (if-not actor
                                        (.send (.code reply 401) (clj->js {:error "Actor not found"}))
                                        (.send reply (clj->js
                                                      {:ok true
                                                       :actor (sanitize-actor (js->clj actor :keywordize-keys true))})))))))))))))

(defn register-config-route!
  "GET /api/auth/config — Public auth configuration."
  [app]
  (.get app "/api/auth/config"
        (fn [_req reply]
          (.send reply (clj->js
                        {:githubEnabled (cfg/get-in-config [:oauth/github-enabled])
                         :publicBaseUrl (cfg/get-in-config [:axxium/public-base-url])
                         :loginUrl "/api/auth/login"
                         :signupUrl "/api/auth/signup"})))))

(defn register-auth-routes!
  "Register all auth routes on the Fastify app."
  [app]
  (register-config-route! app)
  (register-signup-route! app)
  (register-login-route! app)
  (register-logout-route! app)
  (register-me-route! app))
