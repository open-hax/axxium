(ns axxium.domain.identity
  "Identity continuity preserves identifiers; authorization remains local to the recipient."
  (:require [clojure.string :as str]))
(def default-capabilities ["axxium/login" "axxium/read" "axxium/write"])
(def default-roles ["axxium/user"])
(defn normalize-credentials "Normalize the email while retaining the password exactly." [body]
  (assoc body :email (str/lower-case (str/trim (str (or (:email body) ""))))))
(defn public-actor "Expose identity, never credentials or internal session material." [actor]
  (select-keys actor [:id :entity_id :email :display_name :origin_issuer :capabilities :roles :status]))
(defn transfer-payload "Export identity data only; never export passwords or privileges." [actor nonce]
  {:sub (:id actor) :entity_id (:entity_id actor) :email (:email actor)
   :display_name (:display_name actor) :jti nonce :purpose "identity-transfer"})
(defn imported-actor "Use the verified subject with locally assigned default authorization." [claims password-hash]
  {:id (:sub claims) :entity-id (:entity_id claims) :email (:email claims)
   :display-name (:display_name claims) :password-hash password-hash
   :capabilities default-capabilities :roles default-roles :status "active"})
