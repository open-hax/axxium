(ns axxium.extern.node-proof-lease-test
  "Admission and recheck of a leased mailbox session under its configured owner."
  (:require [axxium.config :as cfg]
            [axxium.domain.github-identity :as gi]
            [axxium.domain.state :as state]
            [axxium.extern.github-identity-mcp :as mcp]
            [axxium.extern.token-crypto :as tc]
            [axxium.routes.github-identity :as identity]
            [cljs.test :refer [deftest is]]))

(def ^:private root "did:web:axxium.example:actors:owner")
(def ^:private root-account "actor_11111111-1111-4111-8111-111111111111")
(def ^:private codex (str root ":codex"))
(def ^:private session (str codex ":s-1"))
(def ^:private pol {:policy/repos #{"fixture/a"} :policy/permissions {"contents" "read"}
                    :policy/actions #{:read} :policy/forbid #{}})
(def ^:private fixture-key "axb_k")

(defn- tree [lease]
  (let [lg (:ledger (gi/register-root [] state/empty-state root "Owner" pol (mcp/event-ids)))
        lg (:ledger (gi/provision-actor lg (state/fold lg) root codex "Codex" pol :agent (mcp/event-ids)))
        now (js/Date.)
        lg (:ledger (gi/open-session lg (state/fold lg) codex codex session "s" pol lease now (mcp/event-ids)))]
    (:ledger (gi/issue-bootstrap lg (state/fold lg) codex session (mcp/digest fixture-key) (mcp/event-ids)))))

(deftest a-stored-session-proof-expires-with-its-lease
  (let [ledger (atom (tree (js/Date. (+ (js/Date.now) 60000))))
        proof {:principal {:auth/did session :auth/method :actor-node} :node/digest (mcp/digest fixture-key)}]
    (with-redefs [identity/current-actor-state (fn [] (state/fold @ledger))
                  cfg/get-in-config (fn [[key]]
                                      (when (= :identity/root-operator-binding key)
                                        {:actor-id root-account :root-did root}))]
      (is (identity/node-proof-active? proof))
      ;; Same ledger, but the lease has already ended: rebuild with a past lease
      ;; by replacing the session's recorded lease-until.
      (swap! ledger (fn [lg] (conj lg (-> (last (filter #(= :axxium.resource/registered (:event/type %)) lg))
                                          (assoc :event/body (assoc-in (gi/actor-node (state/fold lg) session)
                                                                       [:resource/metadata :actor/lease-until]
                                                                       (js/Date. (- (js/Date.now) 1000))))))))
      (is (not (identity/node-proof-active? proof)) "S1: an ended lease fails the recheck"))))

(deftest a-mailbox-session-requires-the-configured-owner
  (let [lg (tree (js/Date. (+ (js/Date.now) 60000)))
        other-root "did:web:axxium.example:actors:other"
        st (state/fold (:ledger (gi/register-root lg (state/fold lg) other-root "Other"
                                                  pol (mcp/event-ids))))
        configured-root (atom root)]
    (with-redefs [identity/current-actor-state (constantly st)
                  tc/read-key-file (constantly :configured)
                  cfg/get-in-config (fn [[key]]
                                      (case key
                                        :identity/root-operator-binding
                                        (when-let [did @configured-root]
                                          {:actor-id root-account :root-did did})
                                        :github-identity/ledger-file "fixture-actors.edn"
                                        nil))]
      (let [proof (identity/node-proof session fixture-key)]
        (is (= session (get-in proof [:principal :auth/did])))
        (is (identity/node-proof-active? proof))
        (doseq [outside-owner [other-root nil]]
          (reset! configured-root outside-owner)
          (is (nil? (identity/node-proof session fixture-key)))
          (is (false? (identity/node-proof-active? proof))))))))
