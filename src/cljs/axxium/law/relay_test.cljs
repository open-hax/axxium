(ns axxium.law.relay-test
  (:require [axxium.law.oidc :as oidc]
            [axxium.law.relay :as relay]
            [cljs.test :as t]))

(def grant "Synthetic closed grant fixture." {:node-id "synthetic-node" :secret (apply str (repeat 32 "n"))
            :owner-subject "synthetic-actor" :broker-audience "https://broker.example"
            :expires-at 1000 :enabled true :allowed-models ["test-model"] :allowed-tenants ["local-tenant"]})

(t/deftest closed-operator-authorization
  (t/is (relay/grants-valid? [grant] []))
  (doseq [invalid [(dissoc grant :allowed-tenants) (assoc grant :allowed-tenants [])
                   (assoc grant :allowed-tenants ["local-tenant" "local-tenant"]) (assoc grant :did "did:web:host") (assoc grant :allowed-models ["*"])
                   (assoc grant :allowed-models []) (assoc grant :enabled "true")
                   (assoc grant :secret "short") (assoc grant :expires-at ##Inf)]]
    (t/is (not (relay/grants-valid? [invalid] []))))
  (t/is (not (relay/grants-valid? [grant grant] [])))
  (t/is (not (relay/grants-valid? [grant] [{:secret (:secret grant)}])))
  (doseq [model ["*" "test-*" " model" "model " "model\n" (apply str (repeat 129 "a")) 1 nil]]
    (t/is (not (relay/grants-valid? [(assoc grant :allowed-models [model])] [])) (pr-str model)))
  (doseq [tenant ["*" "tenant-*" "Default" " default" "default " "default\n" "" (apply str (repeat 129 "a")) 1 nil]]
    (t/is (not (relay/grants-valid? [(assoc grant :allowed-tenants [tenant])] [])) (pr-str tenant)))
  (t/is (relay/grants-valid? [(assoc grant :allowed-tenants ["default"])] []))
  (doseq [node ["*" "node*" " node" "node " "node\n" (apply str (repeat 65 "a")) 1 nil]]
    (t/is (not (relay/grants-valid? [(assoc grant :node-id node)] [])) (pr-str node)))
  (t/is (thrown? js/Error (oidc/require-config! {:node-grants [grant]}))))

(t/deftest actor-and-time-admission
  (let [actor {:id "synthetic-actor" :status "active"}]
    (t/is (relay/issuance-admitted? grant actor 900))
    (doseq [[g a t] [[grant actor 1000] [(assoc grant :enabled false) actor 900]
                      [grant (assoc actor :id "other") 900]
                      [grant (assoc actor :status "disabled") 900]
                      [grant nil 900] [(assoc grant :expires-at (+ 901 relay/max-grant-seconds)) actor 900]]]
      (t/is (not (relay/issuance-admitted? g a t))))
    (t/is (= {:iss "https://authority.example/oidc" :sub "synthetic-actor" :aud "https://broker.example"
            :node_id "synthetic-node" :scope "proxx:relay" :jti "test-jti" :iat 990 :exp 1000
            :allowed_models ["test-model"] :allowed_tenants ["local-tenant"] :grant_revision "revision"}
           (relay/claims "https://authority.example/oidc" grant 990 "test-jti" "revision")))))
