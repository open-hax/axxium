(ns axxium.law.relay-test
  (:require [axxium.law.oidc :as oidc]
            [axxium.law.relay :as relay]
            [cljs.test :refer [deftest is]]))

(def grant {:node-id "synthetic-node" :secret (apply str (repeat 32 "n"))
            :owner-subject "synthetic-actor" :broker-audience "https://broker.example"
            :expires-at 1000 :enabled true :allowed-models ["test-model"]})

(deftest closed-operator-authorization
  (is (relay/grants-valid? [grant] []))
  (doseq [invalid [(assoc grant :did "did:web:host") (assoc grant :allowed-models ["*"])
                   (assoc grant :allowed-models []) (assoc grant :enabled "true")
                   (assoc grant :secret "short") (assoc grant :expires-at ##Inf)]]
    (is (not (relay/grants-valid? [invalid] []))))
  (is (not (relay/grants-valid? [grant grant] [])))
  (is (not (relay/grants-valid? [grant] [{:secret (:secret grant)}])))
  (is (thrown? js/Error (oidc/require-config! {:node-grants [grant]}))))

(deftest actor-and-time-admission
  (let [actor {:id "synthetic-actor" :status "active"}]
    (is (relay/issuance-admitted? grant actor 900))
    (doseq [[g a t] [[grant actor 1000] [(assoc grant :enabled false) actor 900]
                      [grant (assoc actor :id "other") 900]
                      [grant (assoc actor :status "disabled") 900]
                      [grant nil 900] [(assoc grant :expires-at (+ 901 relay/max-grant-seconds)) actor 900]]]
      (is (not (relay/issuance-admitted? g a t))))
    (is (= {:iss "https://authority.example/oidc" :sub "synthetic-actor" :aud "https://broker.example"
            :node_id "synthetic-node" :scope "proxx:relay" :jti "test-jti" :iat 990 :exp 1000
            :allowed_models ["test-model"] :grant_revision "revision"}
           (relay/claims "https://authority.example/oidc" grant 990 "test-jti" "revision")))))
