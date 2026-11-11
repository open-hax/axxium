(ns axxium.domain.session-actor-test
  "S1-S3: leased session nodes, self-advertisement at ancestor anchors, and
   find(v, anchor, q) over the advert fold."
  (:require [axxium.domain.github-identity :as gi]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.mailbox-mcp :as mailbox]
            [axxium.domain.policy :as policy]
            [axxium.domain.state :as state]
            [cljs.test :refer [deftest is testing]]))

(def ^:private root "did:web:axxium.example:actors:owner")
(def ^:private other-root "did:web:axxium.example:actors:other")
(def ^:private codex (str root ":codex"))
(def ^:private work (str root ":work"))
(def ^:private grokbot (str root ":grokbot"))
(def ^:private outside (str other-root ":codex"))
(def ^:private s1 (str codex ":s-1"))
(def ^:private s2 (str codex ":s-2"))
(def ^:private w1 (str work ":s-1"))
(def ^:private o1 (str outside ":s-1"))
(def ^:private wide {:policy/repos #{"fixture/a" "fixture/b"} :policy/permissions {"contents" "read"}
                     :policy/actions #{:read} :policy/forbid #{}})
(def ^:private narrow (assoc wide :policy/repos #{"fixture/a"}))
(def ^:private hour 3600000)

(defn- at [ms] (js/Date. (+ (inst-ms fx/t0) ms)))

(defn- counter [] (let [n (atom 700)] (fn [] {:decision-id (fx/event-uuid (swap! n + 2))
                                              :effect-id (fx/event-uuid (inc @n)) :at fx/t0})))

(defn- tree [ids]
  (let [step (fn [lg f] (:ledger (f lg (state/fold lg))))]
    (-> []
        (step #(gi/register-root %1 %2 root "Owner" wide (ids)))
        (step #(gi/register-root %1 %2 other-root "Other" wide (ids)))
        (step #(gi/provision-actor %1 %2 root codex "Codex" wide :agent (ids)))
        (step #(gi/provision-actor %1 %2 root work "Work" wide :agent (ids)))
        (step #(gi/provision-actor %1 %2 root grokbot "Grok Bot" wide :agent (ids)))
        (step #(gi/provision-actor %1 %2 other-root outside "Outside" wide :agent (ids))))))

(defn- open [lg operator parent did pol lease ids]
  (gi/open-session lg (state/fold lg) operator parent did "session" pol lease fx/t0 (ids)))

(deftest sessions-are-leased-attenuated-children
  (let [ids (counter)
        lg (tree ids)
        opened (open lg codex codex s1 narrow (at hour) ids)
        st (state/fold (:ledger opened))]
    (is (some? (:effect opened)))
    (is (= :session (gi/kind-of st s1)))
    (is (policy/decision-before-effect? (:ledger opened)))
    (is (gi/live? st s1 (at (dec hour))))
    (is (not (gi/live? st s1 (at hour))) "S1: an ended lease is not live")
    (is (gi/effective? st s1) "the lease is separate from status (G3)")
    (testing "G1, L7, lease bounds, and who may open"
      (is (nil? (:effect (open lg codex codex s1 (assoc wide :policy/repos #{"*"}) (at hour) ids))))
      (is (nil? (:effect (open lg codex codex (str work ":s-x") narrow (at hour) ids))) "named under its parent")
      (is (nil? (:effect (open lg codex codex s1 narrow (at (inc 86400000)) ids))) "at most a day")
      (is (nil? (:effect (open lg codex codex s1 narrow fx/t0 ids))) "a lease must end in the future")
      (is (nil? (:effect (open lg work codex s1 narrow (at hour) ids))) "a sibling cannot open sessions")
      (is (some? (:effect (open lg root codex s1 narrow (at hour) ids))) "a manager can")
      (is (nil? (:effect (open lg root root (str root ":s-1") narrow (at hour) ids)))
          "a human root has no sessions; its agents do"))
    (testing "a child session never outlives its parent session"
      (let [nested (str s1 ":s-a")]
        (is (nil? (:effect (open (:ledger opened) s1 s1 nested narrow (at (* 2 hour)) ids))))
        (is (some? (:effect (open (:ledger opened) s1 s1 nested narrow (at (dec hour)) ids))))))
    (testing "sessions are not provisioned through the ordinary actor path"
      (is (nil? (:effect (gi/provision-actor lg (state/fold lg) codex s1 "s" narrow :session (ids))))))))

(deftest sessions-self-advertise-and-are-found-within-the-tree
  (let [ids (counter)
        lg (-> (tree ids)
               (open codex codex s1 narrow (at hour) ids) :ledger
               (open codex codex s2 narrow (at (* 2 hour)) ids) :ledger
               (open work work w1 narrow (at hour) ids) :ledger
               (open outside outside o1 narrow (at hour) ids) :ledger)
        adv (fn [lg op did anchor tags] (gi/advertise lg (state/fold lg) op did anchor tags fx/t0 (ids)))
        lg (-> lg
               (adv s1 s1 root #{"pr-66" "review"}) :ledger
               (adv s2 s2 codex #{"pr-67"}) :ledger
               (adv w1 w1 root #{"mailbox"}) :ledger
               (adv o1 o1 other-root #{"review"}) :ledger)
        st (state/fold lg)
        now (at 1000)]
    (is (= [s1 w1] (mapv :actor/did (gi/directory-at st root now))) "S2: D(root) is the advert fold")
    (is (= [s2] (mapv :actor/did (gi/directory-at st codex now))))
    (is (= [s1] (mapv :actor/did (gi/find-actors st grokbot root "review" now))) "S3: query on tags")
    (is (= [w1] (mapv :actor/did (gi/find-actors st grokbot root "Mailbox" now))))
    (is (= [w1] (mapv :actor/did (gi/find-actors st s1 root nil now))) "the viewer is not its own result")
    (is (= [] (gi/find-actors st outside root nil now)) "S3: never across trees")
    (is (= [] (gi/find-actors st grokbot other-root nil now)))
    (is (= [s2] (mapv :actor/did (gi/directory-at st codex (at (inc hour))))) "expired leases drop out")
    (testing "anchors are ancestors-or-self; only the node or a manager advertises"
      (is (nil? (:effect (adv lg s1 s1 work #{}))) "work is not an ancestor of s1")
      (is (nil? (:effect (adv lg w1 s1 root #{}))) "a sibling session cannot advertise s1")
      (is (some? (:effect (adv lg codex s1 codex #{"moved"}))) "the parent manages its session")
      (is (nil? (:effect (adv lg s1 s1 root #{"Not A Tag"}))) "tags are bounded slugs"))
    (testing "withdraw and suspension remove a node from the directory"
      (let [gone (:ledger (gi/withdraw lg st s1 s1 (ids)))
            suspended (:ledger (gi/suspend lg st root codex (ids)))]
        (is (= [w1] (mapv :actor/did (gi/directory-at (state/fold gone) root now))))
        (is (nil? (:effect (gi/withdraw gone (state/fold gone) s1 s1 (ids)))) "withdraw is not repeated")
        (is (= [w1] (mapv :actor/did (gi/directory-at (state/fold suspended) root now)))
            "L2: suspending codex hides its sessions")))))

(deftest discover-actors-takes-an-anchor-and-a-query
  (let [ids (counter)
        lg (-> (tree ids)
               (open codex codex s1 narrow (at hour) ids) :ledger
               (open work work w1 narrow (at hour) ids) :ledger)
        lg (:ledger (gi/advertise lg (state/fold lg) s1 s1 root #{"review"} fx/t0 (ids)))
        tree-state (state/fold lg)
        audience "axxium:sessions"
        sign-in (fn [mlg did]
                  (let [ctx {:principal {:auth/did did :auth/method :actor-node} :audience audience :now (at 1000)}
                        {mlg :ledger {sid :session/id} :result}
                        (mailbox/call mlg ctx :open_session {:audience audience :ttl-ms hour})
                        ctx (assoc ctx :session/id sid :directory/state tree-state :directory/bindings {})]
                    [(:ledger (mailbox/call mlg ctx :register_mailbox {})) ctx]))
        [mlg _] (sign-in [] s1)
        [mlg _] (sign-in mlg w1)
        [mlg viewer] (sign-in mlg grokbot)
        found (fn [args] (mapv :actor/did (get-in (mailbox/call mlg viewer :discover_actors args) [:result :actors])))]
    (is (= [s1 w1] (found {})) "without an anchor: every reachable node but the caller")
    (is (= [s1] (found {:anchor root})) "with an anchor: only what is advertised there")
    (is (= #{"review"} (get-in (mailbox/call mlg viewer :discover_actors {:anchor root}) [:result :actors 0 :advert/tags])))
    (is (= [] (found {:anchor root :query "nothing"})))
    (is (= [w1] (found {:query "work:s-1"})) "query without an anchor matches DID and name")
    (is (= :bad-request (:error (mailbox/call mlg viewer :discover_actors {:anchor "not a did"}))))))
