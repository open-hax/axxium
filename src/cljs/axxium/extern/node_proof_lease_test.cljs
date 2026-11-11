(ns axxium.extern.node-proof-lease-test
  "A stored node proof rechecked later (e.g. webhook delivery) drops out
   once the session's lease ends, not only on fresh authentication."
  (:require [axxium.domain.github-identity :as gi]
            [axxium.domain.state :as state]
            [axxium.extern.github-identity-mcp :as mcp]
            [axxium.routes.github-identity :as identity]
            [cljs.test :refer [deftest is]]))

(def ^:private root "did:web:axxium.example:actors:owner")
(def ^:private codex (str root ":codex"))
(def ^:private session (str codex ":s-1"))
(def ^:private pol {:policy/repos #{"fixture/a"} :policy/permissions {"contents" "read"}
                    :policy/actions #{:read} :policy/forbid #{}})

(defn- tree [lease]
  (let [lg (:ledger (gi/register-root [] state/empty-state root "Owner" pol (mcp/event-ids)))
        lg (:ledger (gi/provision-actor lg (state/fold lg) root codex "Codex" pol :agent (mcp/event-ids)))
        now (js/Date.)
        lg (:ledger (gi/open-session lg (state/fold lg) codex codex session "s" pol lease now (mcp/event-ids)))]
    (:ledger (gi/issue-bootstrap lg (state/fold lg) codex session (mcp/digest "axb_k") (mcp/event-ids)))))

(deftest a-stored-session-proof-expires-with-its-lease
  (let [ledger (atom (tree (js/Date. (+ (js/Date.now) 60000))))
        proof {:principal {:auth/did session :auth/method :actor-node} :node/digest (mcp/digest "axb_k")}]
    (with-redefs [identity/current-actor-state (fn [] (state/fold @ledger))]
      (is (identity/node-proof-active? proof))
      ;; Same ledger, but the lease has already ended: rebuild with a past lease
      ;; by replacing the session's recorded lease-until.
      (swap! ledger (fn [lg] (conj lg (-> (last (filter #(= :axxium.resource/registered (:event/type %)) lg))
                                          (assoc :event/body (assoc-in (gi/actor-node (state/fold lg) session)
                                                                       [:resource/metadata :actor/lease-until]
                                                                       (js/Date. (- (js/Date.now) 1000))))))))
      (is (not (identity/node-proof-active? proof)) "S1: an ended lease fails the recheck"))))
