(ns axxium.auth.token
  "Local session token orchestration. Transfer signatures use separate asymmetric keys."
  (:require [axxium.config :as cfg]
            [axxium.extern.jose :as jose]))
(defn create-token "Mint a unique session for a persisted actor." [actor]
  (jose/sign-session {:sub (:id actor) :jti (str (random-uuid))}
                     (cfg/get-in-config [:jwt/secret])
                     (cfg/get-in-config [:jwt/issuer])
                     (cfg/get-in-config [:jwt/audience])
                     (cfg/get-in-config [:jwt/expiry-hours])))
(defn verify-token "Verify local session claims." [token]
  (jose/verify-session token (cfg/get-in-config [:jwt/secret])
                       (cfg/get-in-config [:jwt/issuer]) (cfg/get-in-config [:jwt/audience])))
