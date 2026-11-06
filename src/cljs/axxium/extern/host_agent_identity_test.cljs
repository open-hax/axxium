(ns axxium.extern.host-agent-identity-test
  "Seeded identity tree, host agents, :api-key credentials and bootstrap
   auth. No network, no secrets in the seed."
  (:require [axxium.domain.github-identity :as gi]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.policy :as policy]
            [axxium.domain.state :as state]
            [axxium.extern.github-identity-mcp :as mcp]
            [clio.shape.edn :as edn]
            [clojure.string :as str]
            [cljs.test :refer [deftest is async testing]]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]))

(def host "axxium.example")
(defn- did [p] (str "did:web:" host ":actors:" p))
(def alice (did "alice"))
(def laptop (did "alice:laptop"))
(def agent (did "alice:laptop:coding-agent"))
(def review-bot (did "alice:bots:review-bot"))

(defn- counter-ids []
  (let [n (atom 1000)]
    (fn [] {:decision-id (fx/event-uuid (swap! n inc)) :effect-id (fx/event-uuid (swap! n inc)) :at fx/t1})))

(defn- seed [] (edn/read-one (fs/readFileSync "resources/identity/example-tree.edn" "utf8")))

(def extra
  "A second agent on the host and a second host, added for isolation tests."
  {:nodes [{:id "alice:laptop:second-agent" :parent "alice:laptop" :kind :agent :name "second"
            :policy :team}
           {:id "alice:desktop" :parent "alice" :kind :host :name "desktop" :policy :team}
           {:id "alice:desktop:coding-agent" :parent "alice:desktop" :kind :agent :name "agent"
            :policy :team}]})
(def second-agent (did "alice:laptop:second-agent"))
(def desktop-agent (did "alice:desktop:coding-agent"))

(defn- seeded [ids]
  (let [s (seed)]
    (gi/seed-ledger (gi/resolve-seed (update s :nodes into (:nodes extra)) host) ids)))

(deftest seed-builds-the-whole-tree
  (let [{:keys [ledger refused]} (seeded (counter-ids))
        st (state/fold ledger)
        under (gi/actors-under st alice)
        kinds (frequencies (map :actor/kind under))]
    (is (= [] refused) "every seeded policy is within its parent's")
    (is (= {:human 1 :group 1 :bot 1 :host 2 :agent 3} kinds))
    (is (= [laptop alice] (gi/ancestors-of st agent)))
    (is (= (did "alice:bots") (gi/parent-of st review-bot)))
    (is (contains? (:policy/forbid (gi/policy-of st agent)) :approve-own))
    (is (policy/decision-before-effect? ledger))
    (is (not (re-find #"(?i)secret|pem|token\"" (pr-str (seed)))) "the seed holds no secrets")))

(deftest host-agents-fetch-only-their-own-credentials
  (async done
    (let [ids (counter-ids)
          deps {:token-key (crypto/randomBytes 32) :ids ids :cache (atom {}) :now (constantly 1e12)
                :fetch (fn [& _] (js/Promise.reject (ex-info "no network" {})))}
          c (fn [lg actor tool args] (mcp/call (assoc deps :ledger lg) {:actor actor} tool args))
          lg0 (:ledger (seeded ids))]
      (-> (c lg0 alice "credential_provision"
             {:actor/did agent :credential/label "openai" :credential/type :api-key
              :credential/secret "sk-test-agent" :credential/provider "openai"
              :credential/env "OPENAI_API_KEY"})
          (.then (fn [{lg :ledger}]
                   (c lg alice "credential_provision"
                      {:actor/did second-agent :credential/label "provider-b" :credential/type :api-key
                       :credential/secret "sk-test-second" :credential/provider "provider-b"
                       :credential/env "PROVIDER_B_KEY"})))
          (.then (fn [{lg :ledger}] (c lg alice "bootstrap_issue" {:actor/did agent})))
          (.then (fn [{lg :ledger {key :bootstrap/key} :result}]
                   (-> (js/Promise.all
                        #js [(c lg agent "bootstrap_issue" {:actor/did second-agent})
                             (c lg agent "credential_provision"
                                {:actor/did agent :credential/label "x" :credential/type :bootstrap-key
                                 :credential/secret "k"})])
                       (.then (fn [[sib self-boot]] [lg key sib self-boot])))))
          (.then (fn [[lg key sib self-boot]]
                   (let [st (state/fold lg)]
                     (is (str/starts-with? key "axb_"))
                     (is (not (str/includes? (pr-str lg) key)) "only the digest is stored")
                     (is (not (str/includes? (pr-str lg) "sk-test-agent")) "api keys are sealed")
                     (is (= :forbidden (:error sib)) "an agent cannot issue a sibling's key")
                     (is (= :invalid (:error self-boot)))
                     (is (= agent (mcp/authenticate st agent key)))
                     (is (nil? (mcp/authenticate st agent "axb_wrong")))
                     (is (nil? (mcp/authenticate st second-agent key)) "a key works only for its own agent")
                     (is (nil? (mcp/authenticate st desktop-agent key))))
                   (-> (c lg agent "credentials_self" {})
                       (.then (fn [own] [lg key own])))))
          (.then (fn [[lg key {{creds :credentials} :result}]]
                   (is (= [{:credential/label "openai" :credential/secret "sk-test-agent"
                            :credential/env "OPENAI_API_KEY" :credential/type :api-key}]
                          (mapv #(select-keys % [:credential/label :credential/secret
                                                 :credential/env :credential/type]) creds))
                       "own api key only; no bootstrap key, no sibling's")
                   (-> (c lg alice "actor_suspend" {:actor/did laptop})
                       (.then (fn [{lg :ledger}]
                                (let [st (state/fold lg)]
                                  (testing "host suspension cascades to its agents"
                                    (is (nil? (mcp/authenticate st agent key)))
                                    (is (gi/effective? st desktop-agent) "the other host is unaffected"))
                                  (c lg agent "credentials_self" {})))))))
          (.then (fn [suspended] (is (= [] (get-in suspended [:result :credentials])))))
          (.catch (fn [e] (is false (pr-str e))))
          (.finally done)))))

(deftest bootstrap-rotation-invalidates-the-old-key
  (async done
    (let [ids (counter-ids)
          deps {:token-key (crypto/randomBytes 32) :ids ids}
          c (fn [lg actor tool args] (mcp/call (assoc deps :ledger lg) {:actor actor} tool args))
          lg0 (:ledger (seeded ids))]
      (-> (c lg0 laptop "bootstrap_issue" {:actor/did agent})
          (.then (fn [{lg :ledger {k1 :bootstrap/key} :result}]
                   (-> (c lg laptop "bootstrap_issue" {:actor/did agent})
                       (.then (fn [{lg :ledger {k2 :bootstrap/key} :result}]
                                (let [st (state/fold lg)]
                                  (is (nil? (mcp/authenticate st agent k1)))
                                  (is (= agent (mcp/authenticate st agent k2)))
                                  (is (= 2 (get-in (gi/credential st agent "bootstrap")
                                                   [:resource/metadata :credential/generation])))))))))
          (.catch (fn [e] (is false (pr-str e))))
          (.finally done)))))

(deftest seed-application-is-idempotent
  (let [ids (counter-ids)
        s (gi/resolve-seed (seed) host)
        first-run (gi/seed-ledger s ids)
        again (gi/apply-seed (:ledger first-run) alice s ids)]
    (is (= 4 (count (:created first-run))))
    (is (= [] (:created again)) "a restart creates nothing")
    (is (= 4 (count (:unchanged again))))
    (is (= [] (:refused again)))
    (is (= (:ledger first-run) (:ledger again)) "no new ledger entries")))

(deftest seed-cannot-exceed-the-operator
  (let [ids (counter-ids)
        lg (:ledger (seeded ids))
        wide {:nodes [{:id "alice:laptop:rogue" :parent "alice:laptop" :kind :agent :name "rogue"
                       :policy {:policy/repos #{"*"} :policy/permissions {} :policy/actions #{:read}
                                :policy/forbid #{:force-push :approve-own :merge-own}}}
                      {:id "alice:bots:x" :parent "alice:bots" :kind :bot :name "x"
                       :policy {:policy/repos #{"example-org/website"} :policy/permissions {}
                                :policy/actions #{:read} :policy/forbid #{:force-push :approve-own :merge-own}}}]}
        out (gi/apply-seed lg laptop (gi/resolve-seed wide host) ids)]
    (is (= [(did "alice:laptop:rogue") (did "alice:bots:x")] (:refused out))
        "G1 (wider than the parent) and G2 (outside the operator's subtree)")))

(deftest export-then-import-round-trips-without-secrets
  (async done
    (let [ids (counter-ids)
          deps {:token-key (crypto/randomBytes 32) :ids ids}
          c (fn [lg actor tool args] (mcp/call (assoc deps :ledger lg) {:actor actor} tool args))
          lg0 (:ledger (seeded ids))]
      (-> (c lg0 alice "credential_provision"
             {:actor/did agent :credential/label "k" :credential/type :api-key
              :credential/secret "sk-export-test"})
          (.then (fn [{lg :ledger}] (c lg laptop "subtree_export" {})))
          (.then (fn [{{exported :seed} :result}]
                   (is (= [agent second-agent] (mapv :id (:nodes exported))))
                   (is (not (str/includes? (pr-str exported) "sk-export-test")))
                   ;; a fresh tree with only alice + laptop, then import laptop's export
                   (let [base (gi/seed-ledger (gi/resolve-seed
                                               (-> (seed) (assoc :nodes [(nth (:nodes (seed)) 2)])) host)
                                              ids)]
                     (c (:ledger base) laptop "subtree_import"
                        {:seed (edn/read-one (pr-str exported)) :host host}))))
          (.then (fn [{lg :ledger {:keys [created refused]} :result}]
                   (is (= [agent second-agent] created))
                   (is (= [] refused))
                   (c lg laptop "subtree_import" {:seed {:nodes [{:id agent :parent laptop :kind :agent
                                                                  :name "a" :policy (gi/policy-of (state/fold lg) agent)}]}
                                                  :host host})))
          (.then (fn [{{:keys [created unchanged]} :result}]
                   (is (= [] created))
                   (is (= [agent] unchanged))))
          (.catch (fn [e] (is false (pr-str e))))
          (.finally done)))))

