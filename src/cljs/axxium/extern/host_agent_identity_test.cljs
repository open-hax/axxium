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
(def err (did "err"))
(def yoga (did "err:hosts:yoga"))
(def codex (did "err:hosts:yoga:codex"))
(def claude (did "err:hosts:yoga:claude-code"))
(def stealth-claude (did "err:hosts:stealth:claude-code"))

(defn- counter-ids []
  (let [n (atom 1000)]
    (fn [] {:decision-id (fx/event-uuid (swap! n inc)) :effect-id (fx/event-uuid (swap! n inc)) :at fx/t1})))

(defn- seed [] (edn/read-one (fs/readFileSync "resources/identity/foresight-tree.edn" "utf8")))

(defn- seeded [ids] (gi/seed-ledger (gi/resolve-seed (seed) host) ids))

(deftest seed-builds-the-whole-tree
  (let [{:keys [ledger refused]} (seeded (counter-ids))
        st (state/fold ledger)
        under (gi/actors-under st err)
        kinds (frequencies (map :actor/kind under))]
    (is (= [] refused) "every seeded policy is within its parent's")
    (is (= {:human 1 :group 1 :bot 27 :host 2 :agent 6} kinds))
    (is (= [yoga err] (gi/ancestors-of st codex)))
    (is (= (did "err:grokbots") (gi/parent-of st (did "err:grokbots:grok-bot"))))
    (is (contains? (:policy/forbid (gi/policy-of st codex)) :approve-own))
    (is (policy/decision-before-effect? ledger))
    (is (not (re-find #"(?i)secret|pem|token\"" (pr-str (seed)))) "the seed holds no secrets")))

(deftest host-agents-fetch-only-their-own-credentials
  (async done
    (let [ids (counter-ids)
          deps {:token-key (crypto/randomBytes 32) :ids ids :cache (atom {}) :now (constantly 1e12)
                :fetch (fn [& _] (js/Promise.reject (ex-info "no network" {})))}
          c (fn [lg actor tool args] (mcp/call (assoc deps :ledger lg) {:actor actor} tool args))
          lg0 (:ledger (seeded ids))]
      (-> (c lg0 err "credential_provision"
             {:actor/did codex :credential/label "openai" :credential/type :api-key
              :credential/secret "sk-test-codex" :credential/provider "openai"
              :credential/env "OPENAI_API_KEY"})
          (.then (fn [{lg :ledger}]
                   (c lg err "credential_provision"
                      {:actor/did claude :credential/label "anthropic" :credential/type :api-key
                       :credential/secret "sk-ant-test" :credential/provider "anthropic"
                       :credential/env "ANTHROPIC_API_KEY"})))
          (.then (fn [{lg :ledger}] (c lg err "bootstrap_issue" {:actor/did codex})))
          (.then (fn [{lg :ledger {key :bootstrap/key} :result}]
                   (-> (js/Promise.all
                        #js [(c lg codex "bootstrap_issue" {:actor/did claude})
                             (c lg codex "credential_provision"
                                {:actor/did codex :credential/label "x" :credential/type :bootstrap-key
                                 :credential/secret "k"})])
                       (.then (fn [[sib self-boot]] [lg key sib self-boot])))))
          (.then (fn [[lg key sib self-boot]]
                   (let [st (state/fold lg)]
                     (is (str/starts-with? key "axb_"))
                     (is (not (str/includes? (pr-str lg) key)) "only the digest is stored")
                     (is (not (str/includes? (pr-str lg) "sk-test-codex")) "api keys are sealed")
                     (is (= :forbidden (:error sib)) "an agent cannot issue a sibling's key")
                     (is (= :invalid (:error self-boot)))
                     (is (= codex (mcp/authenticate st codex key)))
                     (is (nil? (mcp/authenticate st codex "axb_wrong")))
                     (is (nil? (mcp/authenticate st claude key)) "a key works only for its own agent")
                     (is (nil? (mcp/authenticate st stealth-claude key))))
                   (-> (c lg codex "credentials_self" {})
                       (.then (fn [own] [lg key own])))))
          (.then (fn [[lg key {{creds :credentials} :result}]]
                   (is (= [{:credential/label "openai" :credential/secret "sk-test-codex"
                            :credential/env "OPENAI_API_KEY" :credential/type :api-key}]
                          (mapv #(select-keys % [:credential/label :credential/secret
                                                 :credential/env :credential/type]) creds))
                       "own api key only; no bootstrap key, no sibling's")
                   (-> (c lg err "actor_suspend" {:actor/did yoga})
                       (.then (fn [{lg :ledger}]
                                (let [st (state/fold lg)]
                                  (testing "host suspension cascades to its agents"
                                    (is (nil? (mcp/authenticate st codex key)))
                                    (is (gi/effective? st stealth-claude) "the other host is unaffected"))
                                  (c lg codex "credentials_self" {})))))))
          (.then (fn [suspended] (is (= [] (get-in suspended [:result :credentials])))))
          (.catch (fn [e] (is false (pr-str e))))
          (.finally done)))))

(deftest bootstrap-rotation-invalidates-the-old-key
  (async done
    (let [ids (counter-ids)
          deps {:token-key (crypto/randomBytes 32) :ids ids}
          c (fn [lg actor tool args] (mcp/call (assoc deps :ledger lg) {:actor actor} tool args))
          lg0 (:ledger (seeded ids))]
      (-> (c lg0 yoga "bootstrap_issue" {:actor/did codex})
          (.then (fn [{lg :ledger {k1 :bootstrap/key} :result}]
                   (-> (c lg yoga "bootstrap_issue" {:actor/did codex})
                       (.then (fn [{lg :ledger {k2 :bootstrap/key} :result}]
                                (let [st (state/fold lg)]
                                  (is (nil? (mcp/authenticate st codex k1)))
                                  (is (= codex (mcp/authenticate st codex k2)))
                                  (is (= 2 (get-in (gi/credential st codex "bootstrap")
                                                   [:resource/metadata :credential/generation])))))))))
          (.catch (fn [e] (is false (pr-str e))))
          (.finally done)))))
