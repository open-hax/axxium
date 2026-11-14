(ns axxium.config-test
  (:require [axxium.config :as config]
            [axxium.db :as db]
            [axxium.domain.kernel-fixture :as fixture]
            [axxium.extern.lifecycle :as lifecycle]
            [axxium.extern.pg :as pg]
            [cljs.test :refer [deftest is]]))

(deftest schema-timeout-is-explicit-and-bounded
  (doseq [[input expected] [["1000" 1000] ["20000" 20000]
                            ["300000" 300000] ["120000" 120000]]]
    ;; Valid configuration must be accepted, including both interval bounds.
    (is (= [:accepted expected]
           (try [:accepted (config/parse-schema-statement-timeout-ms input)]
                (catch :default _ [:refused])))))
  (doseq [invalid ["" "0" "999" "300001" "20ms" "-1" "1e4"]]
    (is (thrown? js/Error (config/parse-schema-statement-timeout-ms invalid)))))

(deftest mailbox-pilot-requires-two-distinct-dids-including-the-webhook-recipient
  (let [alice fixture/alice
        bob fixture/bob
        carol fixture/carol
        pair (str alice "," bob)]
    (is (nil? (config/mailbox-pilot-allowed-dids nil bob)))
    (is (= #{alice bob} (config/mailbox-pilot-allowed-dids pair bob)))
    (doseq [[input owner] [["" bob] [bob bob] [(str bob "," bob) bob]
                           [(str pair ",") bob] [(str pair "," carol) bob]
                           [(str "alice.example," bob) bob] [pair nil] [pair carol]]]
      (is (thrown? js/Error (config/mailbox-pilot-allowed-dids input owner))))))

(deftest mailbox-directory-requires-unique-explicit-bindings
  (let [alice fixture/alice
        bob fixture/bob
        a "did:web:127.0.0.1:18874:actors:owner:work"
        b "did:web:127.0.0.1%3A18874:actors:owner:codex"]
    (is (nil? (config/mailbox-actor-bindings nil)))
    (is (= {alice a bob b} (config/mailbox-actor-bindings (pr-str [[alice a] [bob b]]))))
    (doseq [value ["" "[]" "{}" "#unknown []" (pr-str [["alice.example" b]]) (pr-str [[alice bob]])
                   (pr-str [[alice a] [alice b]]) (pr-str [[alice a] [bob a]])]]
      (is (thrown? js/Error (config/mailbox-actor-bindings value))))))

(deftest mailbox-bootstrap-actors-are-explicit-and-distinct
  (let [bot "did:web:axxium.test:actors:owner:grokbot"
        agent "did:web:axxium.test:actors:owner:agent"]
    (is (nil? (config/mailbox-bootstrap-actor-dids nil)))
    (is (= #{bot} (config/mailbox-bootstrap-actor-dids bot)))
    (is (= #{bot agent} (config/mailbox-bootstrap-actor-dids (str bot "," agent))))
    (doseq [invalid ["" " " fixture/alice (str bot ",") (str bot "," bot) (str bot ", " agent)]]
      (is (thrown? js/Error (config/mailbox-bootstrap-actor-dids invalid))))))

(deftest bound-bootstrap-pins-a-unique-actor-and-mailbox-pair
  (let [a "did:web:axxium.test:actors:owner:codex"
        b "did:web:axxium.test:actors:owner:agent"]
    (is (nil? (config/mailbox-bound-bootstrap-bindings nil)))
    (is (= {a fixture/alice b fixture/bob}
           (config/mailbox-bound-bootstrap-bindings (pr-str [[fixture/alice a] [fixture/bob b]]))))
    (doseq [pairs [[[fixture/alice a] [fixture/alice b]]
                   [[fixture/alice a] [fixture/bob a]]
                   [[fixture/alice fixture/bob]]
                   [[a a]]
                   [["a.b" b]]]]
      (is (thrown? js/Error (config/mailbox-bound-bootstrap-bindings (pr-str pairs)))))))

(deftest schema-pool-has-only-its-own-statement-timeout
  (let [seen (atom nil)]
    (with-redefs [pg/create-pool! (fn [options] (reset! seen options) :schema-pool)]
      (is (= :schema-pool (db/create-schema-pool! 120000))))
    (is (= 120000 (:statement-timeout-ms @seen)))
    (is (= 1 (:max @seen)))))

(deftest shutdown-bound-tracks-schema-timeout-before-readiness
  (is (= 45000 (lifecycle/shutdown-bound-ms false 20000)))
  (is (= 245000 (lifecycle/shutdown-bound-ms false 120000)))
  (is (= 25000 (lifecycle/shutdown-bound-ms true 120000))))
