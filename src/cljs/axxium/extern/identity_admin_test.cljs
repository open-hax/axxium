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
  (is (= {"example/issues" "write" "issues" "read"}
         (:policy/permissions (admin/json->policy (assoc team-json :permissions
                                                          {:example/issues "write" :issues "read"})))))
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

(defn- ^:async selected-parent-creation []
  (let [ids (counter-ids)
        lg (seeded ids)
        bots (did "alice:bots")
        create {:op "create" :parent laptop :slug "helper" :kind "agent" :policy team-json}
        admin! (fn [ledger caller request]
                 (mcp/admin {:ledger ledger :ids ids} caller request {:read-edn edn/read-one :host host}))
        outside (await (admin! lg laptop (assoc create :parent bots)))
        wider (await (admin! lg alice (assoc-in create [:policy :actions] ["read" "merge"])))
        {:keys [status ledger]} (await (admin! lg alice create))
        st (state/fold ledger)
        child (str laptop ":helper")]
    (is (= 403 (:status outside)) "a caller cannot create under a sibling")
    (is (= 403 (:status wider)) "selected parent limits the child's policy")
    (is (= 200 status))
    (is (= laptop (gi/parent-of st child)))
    (is (= alice (:event/actor (peek ledger))))
    (is (nil? (gi/actor-node st (str alice ":helper"))))
    (let [suspended (await (admin! ledger alice {:op "suspend" :did laptop}))
          under-suspended (await (admin! (:ledger suspended) alice (assoc create :slug "other")))]
      (is (not (gi/effective? (state/fold (:ledger suspended)) child)))
      (is (= 403 (:status under-suspended))))))

(deftest portal-creates-beneath-the-authorized-selected-parent
  (async done
    (-> (selected-parent-creation)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))

(defn- ^:async verify-actor-policy-edits []
  (let [ids (counter-ids)
        before (seeded ids)
        policy-json (fn [lg did] (admin/policy->json (gi/policy-of (state/fold lg) did)))
        admin! (fn [lg caller req]
                 (mcp/admin {:ledger lg :ids ids} caller req {:read-edn edn/read-one :host host}))
        edit (fn [lg did desired]
               {:op "set-policy" :did did :policy desired :expectedPolicy (policy-json lg did)})
        leaf (str laptop ":helper")
        created (await (admin! before laptop {:op "create" :slug "helper" :kind "agent" :policy team-json}))
        lg (:ledger created)
        grant (assoc team-json :permissions {:issues "write"} :actions ["comment"])
        request (edit lg leaf grant)]
    (is (= 200 (:status created)))
    (doseq [invalid [(dissoc request :expectedPolicy)
                     (update request :policy dissoc :forbid)
                     (assoc-in request [:policy :repos] [" "])
                     (assoc-in request [:policy :repos] "example-org/website")
                     (assoc-in request [:policy :permissions] {:issues nil})]]
      (is (= 400 (:status (await (admin! lg alice invalid))))))
    (doseq [[caller denied] [[leaf request]
                              [(did "alice:bots") request]
                              [alice (edit lg alice team-json)]
                              [alice (assoc-in request [:policy :repos] ["*"])]
                              [alice (assoc-in request [:policy :permissions] {:example/issues "write"})]
                              [alice (assoc-in request [:policy :forbid] [])]]]
      (let [out (await (admin! lg caller denied))]
        (is (= 403 (:status out)))
        (is (= (state/fold lg) (state/fold (:ledger out))))))
    (is (not (contains? mcp/agent-tools "actor_set_policy")))
    (let [{:keys [status ledger]} (await (admin! lg laptop request))
          st (state/fold ledger)
          stale (await (admin! ledger alice request))]
      (is (= 200 status))
      (is (= (admin/json->policy grant) (gi/policy-of st leaf)))
      (is (= laptop (gi/parent-of st leaf)))
      (is (= (gi/policy-of (state/fold lg) laptop) (gi/policy-of st laptop)))
      (is (= 409 (:status stale)))
      (is (= {:error "policy-changed"} (:body stale)))
      (is (= st (state/fold (:ledger stale)))))
    (let [suspended (await (admin! before alice {:op "suspend" :did agent}))
          narrowed-too-soon (await (admin! (:ledger suspended) alice (edit (:ledger suspended) laptop team-json)))
          leaf-edit (await (admin! (:ledger suspended) alice (edit (:ledger suspended) agent team-json)))
          parent-edit (await (admin! (:ledger leaf-edit) alice (edit (:ledger leaf-edit) laptop team-json)))
          revoked (await (admin! before alice {:op "revoke" :did agent}))
          on-revoked (await (admin! (:ledger revoked) alice (edit (:ledger revoked) agent team-json)))
          clear (assoc team-json :repos [] :permissions {} :actions [])
          clear-parent (await (admin! (:ledger revoked) alice (edit (:ledger revoked) laptop clear)))]
      (is (= 409 (:status narrowed-too-soon)) "Suspension does not remove a descendant's stored policy.")
      (is (= {:error "descendant-policy-conflict"} (:body narrowed-too-soon)))
      (is (= (state/fold (:ledger suspended)) (state/fold (:ledger narrowed-too-soon))))
      (is (= [200 200] (mapv :status [leaf-edit parent-edit])))
      (is (= 403 (:status on-revoked)))
      (is (= 200 (:status clear-parent)))
      (is (= (admin/json->policy clear) (gi/policy-of (state/fold (:ledger clear-parent)) laptop)))
      (is (policy/decision-before-effect? (:ledger parent-edit))))))

(deftest portal-editing-preserves-policy-bounds-and-checks-the-current-value
  (async done
    (-> (verify-actor-policy-edits)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))

(deftest startup-seed-attributes-nested-events-to-its-root
  (let [ledger (seeded (counter-ids))]
    (is (= laptop (gi/parent-of (state/fold ledger) agent)))
    (is (seq ledger))
    (is (every? #(= alice (:event/actor %)) ledger))))

(defn- ^:async nested-seed-import []
  (let [ids (counter-ids)
        before (seeded ids)
        child (str laptop ":imported")
        seed {:nodes [{:id child :parent laptop :name "Imported Agent" :kind :agent
                       :policy (admin/json->policy team-json)}]}
        {:keys [status body ledger]}
        (await (mcp/admin {:ledger before :ids ids} alice
                          {:op "import" :seed (pr-str seed)}
                          {:read-edn edn/read-one :host host}))
        [decision effect :as added] (subvec ledger (count before))]
    (is (= 200 status))
    (is (= [child] (:created body)))
    (is (= laptop (gi/parent-of (state/fold ledger) child)))
    (is (= [:axxium.policy/decided :axxium.resource/registered] (mapv :event/type added)))
    (is (= [alice alice] (mapv :event/actor added)))
    (is (= alice (get-in decision [:event/body :decision/request :actor])))
    (is (= [(:event/id decision)] (:event/causes effect)))))

(deftest nested-seed-import-preserves-operator-attribution
  (async done
    (-> (nested-seed-import)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))

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

(deftest portal-binds-and-unbinds-mailboxes-under-the-manage-right
  (async done
    (let [ids (counter-ids)
          deps {:token-key (crypto/randomBytes 32) :ids ids}
          box "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"
          a (fn [lg caller req] (mcp/admin (assoc deps :ledger lg) caller req {:read-edn edn/read-one :host host}))]
      (is (= ["mailbox_bind" {:actor/did agent :mailbox/did box}]
             (admin/request->call alice {:op "mailbox-bind" :did agent :mailbox box} edn/read-one host)))
      (is (:error (admin/request->call alice {:op "mailbox-unbind"} edn/read-one host)))
      (-> (a (seeded ids) agent {:op "mailbox-bind" :did agent :mailbox box})
          (.then (fn [{:keys [status ledger]}]
                   (is (= 403 status) "an actor cannot bind its own mailbox")
                   (a ledger laptop {:op "mailbox-bind" :did agent :mailbox box})))
          (.then (fn [{:keys [status ledger]}]
                   (is (= 200 status))
                   (is (= {box agent} (gi/mailbox-bindings (state/fold ledger))))
                   (is (policy/decision-before-effect? ledger))
                   (a ledger alice {:op "mailbox-unbind" :mailbox box})))
          (.then (fn [{:keys [status ledger]}]
                   (is (= 200 status))
                   (is (= {} (gi/mailbox-bindings (state/fold ledger))))))
          (.catch (fn [e] (is false (pr-str e))))
          (.finally done)))))
