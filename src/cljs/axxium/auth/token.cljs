(ns axxium.auth.token
  "JWT token creation and verification using jose.
   Issued tokens are EdDSA signed by this instance's key and carry cred_id
   plus iss, aud, and env. Claim shape is axxium.domain.token; admission is
   axxium.law.token; the header kid is axxium.law.signing."
  (:require [axxium.config :as cfg]
            [axxium.domain.token :as domain]
            [axxium.extern.jose :as jose]
            [axxium.extern.signing-key :as signing-key]
            [axxium.law.token :as law]))

(defn- jwt-options [instance material]
  {:private-key (:private-key material)
   :public-key (:public-key material)
   :kid (:kid material)
   :issuer (:iss instance)
   :audience (domain/audience (:service instance) (:env instance))
   :expiry-hours (cfg/get-in-config [:jwt/expiry-hours])})

(defn- refuse-claims! []
  (throw (ex-info "actor token claims are outside the token law"
                  {:contract :axxium.law.token/valid-token-claims?})))

(defn ^:async create-token
  "Create a JWT for an actor acting through cred-id.
   Returns the token string. Refuses claims outside the token law."
  [actor cred-id]
  (let [instance (cfg/instance)
        material (signing-key/current!)
        claims (domain/actor-claims actor cred-id instance)]
    (when-not (law/valid-token-claims? claims instance)
      (refuse-claims!))
    (await (jose/sign-actor! claims (jwt-options instance material)))))

(defn ^:async verify-token
  "Verify a JWT and return its claims.
   Rejects a bad signature, the wrong issuer, or claims outside the token law,
   including an env that does not match aud."
  [token]
  (let [instance (cfg/instance)
        claims (await (jose/verify-actor! token (jwt-options instance (signing-key/current!))))]
    (if (law/valid-token-claims? claims instance)
      claims
      (refuse-claims!))))
