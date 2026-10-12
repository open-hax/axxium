(ns axxium.routes.auth
  "Axxium password sign-in and public auth configuration."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.extern.bcrypt :as bcrypt]
            [axxium.extern.google :as google]
            [axxium.extern.http :as http]
            [clojure.string :as str]))

(defn- sanitize-actor [actor]
  (dissoc actor :password_hash))

(defn- ^:async signup! [req reply]
  (try
    (let [body (http/body req)
          email (-> (or (:email body) "") str str/trim str/lower-case)
          password (str (or (:password body) ""))
          display-name (-> (or (:display-name body) (:display_name body) email)
                           str str/trim)
          bootstrap-admin-email (-> (cfg/get-in-config [:oauth/bootstrap-admin-email])
                                    str str/trim str/lower-case)]
      (cond
        (str/blank? email)
        (http/send! reply 400 {:error "email is required"})

        (and (seq bootstrap-admin-email) (= email bootstrap-admin-email))
        (http/send! reply 403 {:error "This email is reserved for federated sign-in"})

        (< (count password) 8)
        (http/send! reply 400 {:error "password must be at least 8 characters"})

        (await (db/query-one-sql (db/q-select-actor-by-email {:email email})))
        (http/send! reply 409 {:error "An account with this email already exists"})

        :else
        (let [password-hash (await (bcrypt/hash password
                                                (cfg/get-in-config [:password/salt-rounds])))
              entity-id (str "entity_" (random-uuid))
              actor-id (str "actor_" (random-uuid))]
          (await (db/query-sql (db/q-insert-entity
                                {:id entity-id :kind "human" :email email
                                 :display-name display-name})))
          (await (db/query-sql (db/q-insert-actor
                                {:id actor-id :entity-id entity-id :email email
                                 :display-name display-name :password-hash password-hash
                                 :capabilities [:axxium/login :axxium/read]
                                 :roles [:axxium/user] :status "active"})))
          (let [actor (await (db/query-one-sql (db/q-select-actor-by-id {:id actor-id})))
                {:keys [token]} (await (session/create-session! actor))]
            (session/set-session-cookie reply token)
            (http/send! reply 200 {:ok true :actor (sanitize-actor actor)
                                   :token token})))))
    (catch :default err
      (println "Signup failed:" (http/error-message err))
      (http/send! reply 500 {:error "Signup failed"}))))

(defn- ^:async login! [req reply]
  (try
    (let [body (http/body req)
          email (-> (or (:email body) "") str str/trim str/lower-case)
          password (str (or (:password body) ""))]
      (if (or (str/blank? email) (str/blank? password))
        (http/send! reply 400 {:error "email and password are required"})
        (let [actor (await (db/query-one-sql
                            (db/q-select-actor-by-email-active {:email email})))
              valid? (and (string? (:password_hash actor))
                          (await (bcrypt/compare password (:password_hash actor))))]
          (if valid?
            (let [{:keys [token]} (await (session/create-session! actor))]
              (session/set-session-cookie reply token)
              (http/send! reply 200 {:ok true :actor (sanitize-actor actor)
                                     :token token}))
            (http/send! reply 401 {:error "Invalid email or password"})))))
    (catch :default err
      (println "Login failed:" (http/error-message err))
      (http/send! reply 500 {:error "Login failed"}))))

(defn- ^:async logout! [req reply]
  (when-let [token (session/extract-auth-token req)]
    (await (session/delete-session! token)))
  (session/clear-session-cookie reply)
  (http/send! reply 200 {:ok true}))

(defn- ^:async me! [req reply]
  (if-let [context (await (session/resolve-auth-context req))]
    (if-let [actor (await (db/query-one-sql
                           (db/q-select-actor-by-id {:id (:auth/actor-id context)})))]
      (http/send! reply 200 {:ok true :actor (sanitize-actor actor)})
      (http/send! reply 401 {:error "Actor not found"}))
    (http/send! reply 401 {:error "Unauthorized"})))

(defn- config! [_req reply]
  (http/send! reply 200
              {:githubEnabled false
               :googleEnabled (boolean (google/configured?))
               :googleLoginUrl "/api/auth/google/start"
               :atprotoEnabled true
               :atprotoLoginUrl "/api/auth/atproto/start"
               :publicBaseUrl (cfg/get-in-config [:axxium/public-base-url])
               :loginUrl "/api/auth/login"
               :signupUrl "/api/auth/signup"}))

(defn register-auth-routes! [app]
  (http/get! app "/api/auth/config" config!)
  (http/post! app "/api/auth/signup" signup!)
  (http/post! app "/api/auth/login" login!)
  (http/post! app "/api/auth/logout" logout!)
  (http/get! app "/api/auth/me" me!))
