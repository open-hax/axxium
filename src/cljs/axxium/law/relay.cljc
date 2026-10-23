(ns axxium.law.relay
  "Operator node-grant contracts. Browser identity supplies no machine authority."
  (:require [malli.core :as m]))

(def max-grant-seconds "Maximum operator activation window." (* 30 86400))
(def lease-seconds "Hard maximum signed capability lifetime." 25)
(def endpoint "Fixed machine-only issuance endpoint." "/api/federation/relay-lease")
(def model-schema "Exact names only; no regexes, wildcards or implicit prefixes."
  [:re #"[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"])
(def grant-schema
  "Closed authorization entry; expiration is an integer Unix second."
  [:map {:closed true}
   [:node-id [:re #"[A-Za-z0-9][A-Za-z0-9_-]{0,63}"]]
   [:secret [:string {:min 32 :max 512}]]
   [:owner-subject [:string {:min 1 :max 128}]]
   [:broker-audience [:string {:min 1 :max 2048}]]
   [:expires-at pos-int?]
   [:enabled :boolean]
   [:allowed-models [:vector {:min 1 :max 128} model-schema]]
   [:allowed-tenants [:vector {:min 1 :max 64} [:string {:min 1 :max 256}]]]])

(defn grants-valid?
  "Require unique nodes, distinct node/browser secrets and bounded exact models."
  [grants clients]
  (and (m/validate [:vector {:max 64} grant-schema] grants)
       (= (count grants) (count (set (map :node-id grants))))
       (let [secrets (concat (map :secret grants) (map :secret clients))]
         (= (count secrets) (count (set secrets))))
       (every? #(and (= (count (:allowed-tenants %)) (count (set (:allowed-tenants %))))
                      (= (count (:allowed-models %)) (count (set (:allowed-models %))))
                      (<= (reduce + (map count (:allowed-models %))) 4096)) grants)))

(defn issuance-admitted?
  "Current operator grant and existing active actor must agree exactly."
  [grant actor now]
  (and grant (true? (:enabled grant))
       (integer? now) (< now (:expires-at grant))
       (<= (- (:expires-at grant) now) max-grant-seconds)
       (= (:owner-subject grant) (:id actor)) (= "active" (:status actor))))

(defn claims
  "Construct only fixed relay authority from validated operator data."
  [issuer grant now jti revision]
  {:iss issuer :sub (:owner-subject grant) :aud (:broker-audience grant)
   :node_id (:node-id grant) :scope "proxx:relay" :jti jti :iat now
   :exp (min (+ now lease-seconds) (:expires-at grant))
   :allowed_models (:allowed-models grant) :allowed_tenants (:allowed-tenants grant) :grant_revision revision})
