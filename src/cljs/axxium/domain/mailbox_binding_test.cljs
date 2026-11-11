(ns axxium.domain.mailbox-binding-test
  "B1-B3: mailbox bindings are ledger resources under the owner's manage right."
  (:require [axxium.domain.github-identity :as gi]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.policy :as policy]
            [axxium.domain.state :as state]
            [cljs.test :refer [deftest is testing]]))

(def ^:private root "did:web:axxium.example:actors:owner")
(def ^:private other-root "did:web:axxium.example:actors:other")
(def ^:private work (str root ":work"))
(def ^:private codex (str root ":codex"))
(def ^:private outside (str other-root ":codex"))
(def ^:private policy {:policy/repos #{"fixture/test"} :policy/permissions {"contents" "read"}
                       :policy/actions #{:read} :policy/forbid #{}})

(defn- ids [n] {:decision-id (fx/event-uuid n) :effect-id (fx/event-uuid (inc n)) :at fx/t0})

(defn- counter []
  (let [n (atom 500)] (fn [] (ids (swap! n + 2)))))

(defn- tree []
  (let [lg (:ledger (gi/register-root [] state/empty-state root "Owner" policy (ids 1)))
        lg (:ledger (gi/register-root lg (state/fold lg) other-root "Other" policy (ids 3)))
        lg (:ledger (gi/provision-actor lg (state/fold lg) root work "Work" policy :agent (ids 5)))
        lg (:ledger (gi/provision-actor lg (state/fold lg) root codex "Codex" policy :agent (ids 7)))]
    (:ledger (gi/provision-actor lg (state/fold lg) other-root outside "Outside" policy :agent (ids 9)))))

(defn- bind [lg operator mailbox actor n]
  (gi/bind-mailbox lg (state/fold lg) operator mailbox actor (ids n)))

(deftest a-managing-ancestor-binds-and-the-binding-folds-from-the-ledger
  (let [lg (tree)
        out (bind lg root fx/alice work 11)]
    (is (some? (:effect out)))
    (is (= {fx/alice work} (gi/mailbox-bindings (state/fold (:ledger out)))))
    (is (policy/decision-before-effect? (:ledger out)))
    (testing "B2: siblings, other trees and the actor itself may not bind"
      (is (nil? (:effect (bind lg codex fx/alice work 13))))
      (is (nil? (:effect (bind lg other-root fx/alice work 15))))
      (is (nil? (:effect (bind lg work fx/alice work 17)))))
    (testing "refusals are recorded decisions, never effects"
      (let [refused (:ledger (bind lg codex fx/alice work 13))]
        (is (= (inc (count lg)) (count refused)))
        (is (= {} (gi/mailbox-bindings (state/fold refused))))))))

(deftest bindings-are-one-to-one-and-need-an-effective-actor
  (let [lg (:ledger (bind (tree) root fx/alice work 11))
        suspended (:ledger (gi/suspend lg (state/fold lg) root codex (ids 13)))]
    (is (nil? (:effect (bind lg root fx/alice codex 15))) "a bound mailbox cannot move")
    (is (nil? (:effect (bind lg root fx/bob work 17))) "an actor has one bound mailbox")
    (is (nil? (:effect (bind suspended root fx/bob codex 19))) "B3: only an effective actor is bound")
    (is (nil? (:effect (bind lg root codex work 21))) "an actor DID is never an external mailbox")
    (is (some? (:effect (bind lg root fx/bob codex 23))))))

(deftest unbinding-ends-routing-and-frees-the-mailbox
  (let [lg (:ledger (bind (tree) root fx/alice work 11))
        unbound (gi/unbind-mailbox lg (state/fold lg) root fx/alice (ids 13))
        st (state/fold (:ledger unbound))]
    (is (some? (:effect unbound)))
    (is (= {} (gi/mailbox-bindings st)))
    (is (nil? (:effect (gi/unbind-mailbox lg (state/fold lg) codex fx/alice (ids 15))))
        "B2: a sibling cannot unbind")
    (is (nil? (:effect (gi/unbind-mailbox (:ledger unbound) st root fx/alice (ids 17))))
        "unbinding twice has no effect")
    (is (= {fx/alice codex}
           (gi/mailbox-bindings (state/fold (:ledger (bind (:ledger unbound) root fx/alice codex 19))))))))

(deftest the-env-seed-is-applied-once-and-never-moves-a-ledger-binding
  (let [ids (counter)
        seeded (gi/seed-bindings (tree) {fx/alice work fx/bob codex fx/carol outside} ids)
        again (gi/seed-bindings (:ledger seeded) {fx/alice work fx/bob codex} ids)
        moved (gi/seed-bindings (:ledger seeded) {fx/alice codex} ids)]
    (is (= #{fx/alice fx/bob fx/carol} (set (:created seeded))))
    (is (= {fx/alice work fx/bob codex fx/carol outside} (gi/mailbox-bindings (state/fold (:ledger seeded)))))
    (is (= [] (:created again)))
    (is (= #{fx/alice fx/bob} (set (:unchanged again))))
    (is (= (:ledger seeded) (:ledger again)) "an applied seed appends nothing")
    (is (= [fx/alice] (:refused moved)))
    (is (= work (get (gi/mailbox-bindings (state/fold (:ledger moved))) fx/alice)))
    (is (= [fx/alice] (:refused (gi/seed-bindings (tree) {fx/alice "did:web:axxium.example:actors:ghost"} ids)))
        "a seed for an unknown actor stays unbound")))
