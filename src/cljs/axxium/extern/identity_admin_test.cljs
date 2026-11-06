(ns axxium.extern.identity-admin-test
  "The portal identity manager's handler: JSON requests through the same
   policy-checked tools, write-only secrets, one-time bootstrap keys."
  (:require [axxium.domain.github-identity :as gi]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.policy :as policy]
            [axxium.domain.state :as state]
            [axxium.extern.github-identity-mcp :as mcp]
            [axxium.law.identity-admin :as admin]
            [clio.shape.edn :as edn]
            [clojure.string :as str]
            [cljs.test :refer [deftest is async]]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]))

(def host "axxium.example")
(defn- did [p] (str "did:web:" host ":actors:" p))
(def alice (did "alice"))
(def laptop (did "alice:laptop"))
(def agent (did "alice:laptop:coding-agent"))

(def team-json {:repos ["example-org/website"] :permissions {:contents "read"}
                :actions ["read"] :forbid ["force-push" "approve-own" "merge-own"]})

(defn- counter-ids []
  (let [n (atom 5000)]
    (fn [] {:decision-id (fx/event-uuid (swap! n inc)) :effect-id (fx/event-uuid (swap! n inc)) :at fx/t1})))

(defn- seeded [ids]
  (:ledger (gi/seed-ledger (gi/resolve-seed (edn/read-one (fs/readFileSync "resources/identity/example-tree.edn" "utf8")) host) ids)))

(deftest request-translation
  (is (= {:error "unknown op"} (admin/request->call alice {:op "drop-table"} edn/read-one host)))
  (is (:error (admin/request->call alice {:op "create" :slug "Bad Slug" :kind "bot" :policy team-json} edn/read-one host)))
  (is (:error (admin/request->call alice {:op "create" :slug "x" :kind "human" :policy team-json} edn/read-one host))
      "a root cannot be created from the portal")
  (is (= ["actor_provision" {:actor/did (str alice ":ci") :actor/name "ci" :actor/kind :bot
                             :actor/policy {:policy/repos #{"example-org/website"}
                                            :policy/permissions {"contents" "read"}
                                            :policy/actions #{:read}
                                            :policy/forbid #{:force-push :approve-own :merge-own}}}]
         (admin/request->call alice {:op "create" :slug "ci" :kind "bot" :policy team-json} edn/read-one host)))
  (is (:error (admin/request->call alice {:op "credential-provision" :did agent :label "k"
                                          :type "bootstrap-key" :secret "s"} edn/read-one host))
      "bootstrap keys are issued, never provisioned")
  (is (:error (admin/request->call alice {:op "import" :seed "[not a map]"} edn/read-one host))))

(deftest views-never-carry-secrets
  (is (= {:ok true} (admin/result->view "credential-provision" {:credential/secret "s"} pr-str {})))
  (is (= {:bootstrapKey "axb_x" :shownOnce true}
         (admin/result->view "bootstrap-issue" {:actor/did agent :bootstrap/key "axb_x"} pr-str {}))))

(deftest admin-handler-end-to-end
  (async done
    (let [ids (counter-ids)
          deps {:token-key (crypto/randomBytes 32) :ids ids}
          lg0 (seeded ids)
          a (fn [lg caller req] (mcp/admin (assoc deps :ledger lg) caller req {:read-edn edn/read-one :host host}))]
      (-> (a lg0 laptop {:op "list"})
          (.then (fn [{:keys [status body]}]
                   (is (= 200 status))
                   (is (= [laptop agent] (mapv :did (:actors body))) "only the caller's subtree")
                   (is (= ["example-org/website" "example-org/widgets"] (get-in body [:actors 0 :policy :repos])))
                   (a lg0 laptop {:op "create" :slug "helper" :kind "agent" :policy team-json})))
          (.then (fn [{:keys [status ledger]}]
                   (is (= 200 status))
                   (is (gi/actor-node (state/fold ledger) (str laptop ":helper")))
                   (-> (a ledger laptop {:op "create" :slug "wide" :kind "agent"
                                         :policy (assoc team-json :repos ["*"])})
                       (.then (fn [wide] (is (= 403 (:status wide)) "G1 through the portal")
                                ledger)))))
          (.then (fn [lg]
                   (-> (a lg laptop {:op "credential-provision" :did agent :label "openai"
                                     :type "api-key" :secret "sk-portal-test" :env "OPENAI_API_KEY"})
                       (.then (fn [{:keys [status body ledger]}]
                                (is (= 200 status))
                                (is (= {:ok true} body))
                                (is (not (str/includes? (pr-str ledger) "sk-portal-test")) "sealed")
                                ledger)))))
          (.then (fn [lg]
                   (-> (a lg laptop {:op "list"})
                       (.then (fn [{:keys [body]}]
                                (is (not (str/includes? (pr-str body) "sk-portal-test")) "never displayed back")
                                (a lg laptop {:op "bootstrap-issue" :did agent})))
                       (.then (fn [{:keys [body ledger]}]
                                (is (str/starts-with? (:bootstrapKey body) "axb_"))
                                (is (= agent (mcp/authenticate (state/fold ledger) agent (:bootstrapKey body))))
                                (is (policy/decision-before-effect? ledger))
                                ledger)))))
          (.then (fn [lg]
                   (-> (a lg agent {:op "suspend" :did laptop})
                       (.then (fn [r] (is (= 403 (:status r)) "a child cannot suspend its parent")
                                (a lg alice {:op "suspend" :did laptop})))
                       (.then (fn [{:keys [ledger]}]
                                (is (not (gi/effective? (state/fold ledger) agent)) "cascades")
                                (a ledger alice {:op "export"})))
                       (.then (fn [{:keys [body]}]
                                (let [seed (edn/read-one (:edn body))]
                                  (is (some #(= agent (:id %)) (:nodes seed)))
                                  (is (not (re-find #"sk-|axb_|sealed" (:edn body))))))))))
          (.catch (fn [e] (is false (pr-str e))))
          (.finally done)))))

