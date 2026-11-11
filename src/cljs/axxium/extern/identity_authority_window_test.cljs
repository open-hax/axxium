(ns axxium.extern.identity-authority-window-test
  "The owner-authority read: one fold per committed ledger, never a second
   writer acquisition, and a committed suspension is seen by the next check."
  (:require [axxium.domain.github-identity :as gi]
            [axxium.domain.state :as state]
            [axxium.extern.ledger-queue :as queue]
            [axxium.routes.github-identity :as identity]
            [cljs.test :refer [async deftest is]]))

(def ^:private root "did:web:axxium.example:actors:owner")
(def ^:private work (str root ":work"))
(def ^:private policy {:policy/repos #{"fixture/test"} :policy/permissions {"contents" "read"}
                       :policy/actions #{:read} :policy/forbid #{}})

(defn- ids [n]
  {:decision-id (str "00000000-0000-4000-8000-" (.padStart (str n) 12 "0"))
   :effect-id (str "00000000-0000-4000-8000-" (.padStart (str (inc n)) 12 "0"))
   :at #inst "2026-11-11T00:00:00.000Z"})

(defn- tree []
  (let [lg (:ledger (gi/register-root [] state/empty-state root "Owner" policy (ids 1)))]
    (:ledger (gi/provision-actor lg (state/fold lg) root work "Work" policy :agent (ids 3)))))

(defn- authorized? [st] (gi/active-owner-delegation? st root work))

(defn- ^:async authority-window* []
  (let [ledger (atom (tree))]
    (with-redefs [queue/current-ledger (fn [_ _] @ledger)]
      (let [first-read (identity/current-actor-state)]
        (is (identical? first-read (identity/current-actor-state))
            "an unchanged ledger is folded once")
        (is (authorized? first-read)))
      (let [inside (await (identity/with-current-actor-state!
                           (fn [_] (authorized? (identity/current-actor-state)))))]
        (is (true? (:result inside)) "the unlocked read inside the writer does not wait on the writer"))
      (swap! ledger #(:ledger (gi/suspend % (state/fold %) root work (ids 5))))
      (is (not (authorized? (identity/current-actor-state)))
          "a committed suspension is seen by the next check"))))

(deftest owner-authority-reads-the-committed-tree-once
  (async done
    (-> (authority-window*)
        (.catch (fn [e] (is false (str "authority-window rejected: " (pr-str e)))))
        (.finally done))))

(deftest owner-delegation-needs-a-live-root-and-child
  (let [lg (tree)
        st (state/fold lg)
        suspended (state/fold (:ledger (gi/suspend lg st root work (ids 7))))]
    (is (authorized? st))
    (is (not (gi/active-owner-delegation? st work root)) "a child never delegates its root")
    (is (not (gi/active-owner-delegation? st root root)) "the root is not its own strict descendant")
    (is (not (authorized? suspended)) "the child's own status still counts")))
