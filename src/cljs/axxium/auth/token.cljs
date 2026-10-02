(ns axxium.auth.token
  "JWT token creation and verification using jose.
   Tokens carry the auth context that downstream services consume."
  (:require [axxium.config :as cfg]
            [axxium.extern.jose :as jose]))

(defn- jwt-options []
  {:secret (cfg/get-in-config [:jwt/secret])
   :issuer (cfg/get-in-config [:jwt/issuer])
   :audience (cfg/get-in-config [:jwt/audience])
   :expiry-hours (cfg/get-in-config [:jwt/expiry-hours])})

(defn create-token
  "Create a JWT for an actor with their capabilities.
   Returns promise of token string."
  [actor]
  (let [claims {:sub (:id actor)
                :entity-id (:entity_id actor)
                :email (:email actor)
                :capabilities (:capabilities actor)
                :roles (:roles actor)
                :status (:status actor)}]
    (jose/sign-actor! claims (jwt-options))))

(defn verify-token
  "Verify a JWT and return the claims.
   Returns promise of verified payload or throws."
  [token]
  (jose/verify-actor! token (jwt-options)))
