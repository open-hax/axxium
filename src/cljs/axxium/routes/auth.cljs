(ns axxium.routes.auth
  "Registration, local authentication and explicit cross-instance identity transfer."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.domain.identity :as identity]
            [axxium.extern.bcrypt :as bcrypt]
            [axxium.extern.http :as http]
            [axxium.infra.identity :as store]
            [axxium.infra.transfer :as transfer]
            [axxium.law.identity :as law]))
(defn- credentials! [request]
  (let [body (identity/normalize-credentials (:body request))]
    (law/require-valid! law/credentials body)
    (when-not (bcrypt/within-limit? (:password body))
      (throw (ex-info "Password exceeds the supported byte length" {:status 400 :code "invalid_request"})))
    body))
(defn- ^:async session-response! [actor]
  {:body {:ok true :actor (identity/public-actor actor)}
   :session-token (await (session/create-session! actor))})
(defn ^:async signup! "Register a local identity atomically; privileges are local defaults." [request]
  (let [body (credentials! request)
        name (or (not-empty (:display_name body)) (:email body))
        _ (law/require-valid! [:string {:min 1 :max 200}] name)
        actor {:id (str "actor_" (random-uuid)) :entity-id (str "entity_" (random-uuid))
               :email (:email body) :display-name name
               :password-hash (await (bcrypt/hash (:password body) (cfg/get-in-config [:password/salt-rounds])))
               :capabilities identity/default-capabilities :roles identity/default-roles :status "active"}
        persisted (await (store/register! actor (cfg/get-in-config [:axxium/public-base-url]) nil))]
    (await (session-response! persisted))))
(defn ^:async login! "Authenticate against this recipient's persisted password and account." [request]
  (let [{:keys [email password]} (credentials! request)
        actor (await (db/query-one-sql (db/q-select-actor-by-email-active email)))]
    (when-not (and actor (await (bcrypt/compare password (:password_hash actor))))
      (throw (ex-info "Invalid email or password" {:status 401 :code "invalid_credentials"})))
    (await (session-response! actor))))
(defn ^:async me! "Return only the authenticated identity." [request]
  {:body {:ok true :actor (identity/public-actor (await (session/require-actor! request)))}})
(defn ^:async logout! "Revoke the current session before clearing its browser cookie." [request]
  (when-let [value (session/extract-auth-token request)] (await (session/delete-session! value)))
  {:body {:ok true} :clear-session? true})
(defn ^:async export! "Require current authentication and password reauthentication before transfer." [request]
  (let [actor (await (session/require-actor! request))
        {:keys [password recipient]} (:body request)]
    (when-not (and (string? password) (bcrypt/within-limit? password)
                   (await (bcrypt/compare password (:password_hash actor))))
      (throw (ex-info "Re-enter your password to copy this identity" {:status 401 :code "reauthentication_required"})))
    (law/require-valid! [:string {:min 1 :max 300}] recipient)
    {:body {:ok true :recipient recipient :expires_in 300
            :transfer (await (transfer/export! actor recipient))}}))
(defn ^:async import! "Consume a verified transfer once and set an independent recipient password." [request]
  (let [claims (await (transfer/verify! (get-in request [:body :transfer])))
        password (get-in request [:body :password])
        _ (credentials! {:body {:email (:email claims) :password password}})
        hash (await (bcrypt/hash password (cfg/get-in-config [:password/salt-rounds])))
        actor (await (store/register! (identity/imported-actor claims hash) (:iss claims) (:jti claims)))]
    (await (session-response! actor))))
(defn register-auth-routes! "Register public configuration and authenticated account operations." [app]
  (doseq [[method path handler]
          [["GET" "/api/auth/config" (fn [_] {:body {:publicBaseUrl (cfg/get-in-config [:axxium/public-base-url])
                                                     :recipients (transfer/trusted-recipients)
                                                     :signupUrl "/api/auth/signup" :loginUrl "/api/auth/login"}})]
           ["POST" "/api/auth/signup" signup!] ["POST" "/api/auth/login" login!]
           ["POST" "/api/auth/logout" logout!] ["GET" "/api/auth/me" me!]
           ["POST" "/api/identity/export" export!] ["POST" "/api/identity/import" import!]
           ["GET" "/.well-known/axxium-identity" (fn [_] {:body {:issuer (cfg/get-in-config [:axxium/public-base-url])
                                                                  :key (transfer/public-key)}})]]]
    (http/route! app method path handler cfg/config)))
