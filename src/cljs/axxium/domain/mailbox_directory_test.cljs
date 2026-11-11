(ns axxium.domain.mailbox-directory-test
  (:require [axxium.domain.github-identity :as gi]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.mailbox-mcp :as mailbox]
            [axxium.domain.policy :as policy]
            [axxium.domain.state :as state]
            [cljs.test :refer [deftest is testing]]))

(def ^:private audience "axxium:owner-directory")
(def ^:private root "did:web:axxium.example:18874:actors:owner")
(def ^:private other-root "did:web:axxium.example:18874:actors:other")
(def ^:private work (str root ":chatgpt:work"))
(def ^:private codex (str root ":codex"))
(def ^:private outside (str other-root ":codex"))
(def ^:private unregistered (str root ":unregistered"))
(def ^:private no-mailbox "did:plc:dddddddddddddddddddddddd")
(def ^:private bindings {fx/alice work fx/bob codex fx/carol outside no-mailbox unregistered})
(def ^:private github-policy {:policy/repos #{"fixture/test"} :policy/permissions {"contents" "read"}
                              :policy/actions #{:read} :policy/forbid #{}})

(defn- ids [n]
  {:decision-id (fx/event-uuid n) :effect-id (fx/event-uuid (inc n)) :at fx/t0})

(defn- provision [lg owner did label n]
  (:ledger (gi/provision-actor lg (state/fold lg) owner did label github-policy :agent (ids n))))

(defn- identity-tree []
  (let [lg (:ledger (gi/register-root [] state/empty-state root "Owner" github-policy (ids 101)))
        lg (:ledger (gi/register-root lg (state/fold lg) other-root "Other" github-policy (ids 103)))]
    (-> lg
        (provision root work "Work" 105)
        (provision root codex "Codex" 107)
        (provision other-root outside "Outside Codex" 109)
        (provision root unregistered "Offline" 111))))

(defn- sign-in [lg did]
  (let [ctx {:principal {:auth/did did :auth/method :atproto} :audience audience :now fx/t0}
        {lg :ledger {sid :session/id} :result} (mailbox/call lg ctx :open_session
                                                          {:audience audience :ttl-ms 3600000})
        ctx (assoc ctx :session/id sid)]
    [(:ledger (mailbox/call lg ctx :register_mailbox {})) ctx]))

(defn- fixture []
  (let [[lg sender] (sign-in [] fx/alice)
        [lg recipient] (sign-in lg fx/bob)
        [lg outsider] (sign-in lg fx/carol)
        tree (identity-tree)]
    {:lg lg :tree tree :sender sender :recipient recipient :outsider outsider}))

(defn- directory-ctx [ctx tree]
  (assoc ctx :directory/state (state/fold tree) :directory/bindings bindings))

(defn- discover [lg ctx]
  (mailbox/call lg ctx :discover_actors {}))

(defn- send [lg ctx recipient]
  (mailbox/call lg ctx :send_message {:to recipient :idempotency-key "message" :body {:text "hi"}}))

(deftest discovers-only-active-owned-actors-with-a-mailbox
  (let [{:keys [lg tree sender outsider]} (fixture)
        scoped (directory-ctx sender tree)
        discovered (discover lg scoped)]
    (is (= lg (:ledger discovered)))
    (is (= #{{:actor/did work :actor/name "Work" :actor/kind :agent :mailbox-did fx/alice}
             {:actor/did codex :actor/name "Codex" :actor/kind :agent :mailbox-did fx/bob}}
           (set (get-in discovered [:result :actors]))))
    (is (= [outside] (mapv :actor/did (get-in (discover lg (directory-ctx outsider tree)) [:result :actors]))))
    (is (= :forbidden (:error (discover lg (assoc scoped :directory/bindings {})))))
    (is (= :forbidden (:error (discover lg (dissoc scoped :directory/state)))))
    (is (= :unknown-tool (:error (discover lg sender))))))

(deftest same-owner-send-uses-audited-mailbox-admission
  (let [{:keys [lg tree sender recipient outsider]} (fixture)
        scoped (directory-ctx sender tree)
        sent (send lg scoped fx/bob)
        sent-ledger (:ledger sent)
        accepted (first (mailbox/messages sent-ledger fx/bob))
        outbound-reply (send sent-ledger (directory-ctx recipient tree) fx/alice)]
    (is (= :forbidden (:error (send lg sender fx/bob))))
    (is (= (:message/id accepted) (get-in sent [:result :message/id])))
    (is (string? (:message/id accepted)))
    (is (= fx/alice (:message/from accepted)))
    (is (= :rule (get-in (nth sent-ledger (count lg)) [:event/body :decision/reason])))
    (is (policy/decision-before-effect? sent-ledger))
    (is (true? (:replayed? (send sent-ledger scoped fx/bob))))
    (is (= 1 (count (mailbox/messages (:ledger outbound-reply) fx/alice))))
    (is (= :forbidden (:error (mailbox/call sent-ledger scoped :read_inbox {:mailbox fx/bob}))))
    (is (= :forbidden (:error (send lg scoped no-mailbox))))
    (testing "An explicit send grant for an outsider does not bypass the owner relation"
      (let [granted (:ledger (mailbox/call lg outsider :grant_send {:to fx/alice}))]
        (is (nil? (:error (send granted sender fx/carol))))
        (is (= :forbidden (:error (send granted scoped fx/carol))))
        (is (= :forbidden (:error (mailbox/call granted scoped :resolve_address {:address fx/carol}))))))))

(deftest owner-directory-rechecks-authority-before-idempotent-receipts
  (let [{:keys [lg tree sender recipient]} (fixture)
        scoped (directory-ctx sender tree)
        accepted (:ledger (send lg scoped fx/bob))
        suspended (:ledger (gi/suspend tree (state/fold tree) root codex (ids 113)))
        revoked (:ledger (mailbox/call accepted recipient :revoke_send {:to fx/alice}))
        restored (:ledger (mailbox/call revoked recipient :grant_send {:to fx/alice}))]
    (is (= :forbidden (:error (send accepted (directory-ctx sender suspended) fx/bob))))
    (is (= [work] (mapv :actor/did (get-in (discover accepted (directory-ctx sender suspended)) [:result :actors]))))
    (is (= :forbidden (:error (send revoked scoped fx/bob))))
    (is (= [work] (mapv :actor/did (get-in (discover revoked scoped) [:result :actors]))))
    (is (= :forbidden (:error (mailbox/call revoked scoped :resolve_address {:address fx/bob}))))
    (is (true? (:replayed? (send restored scoped fx/bob))))))

(deftest owner-delegation-preserves-actor-ids-and-checks-the-active-child
  (let [{:keys [lg tree sender]} (fixture)
        current (state/fold tree)
        suspended (state/fold (:ledger (gi/suspend tree current root work (ids 113))))
        delegated (-> sender
                      (assoc-in [:principal :auth/method] :owner-delegation)
                      (assoc :events/only? true :mailbox/actors? true)
                      (directory-ctx tree))]
    (is (gi/active-owner-delegation? current root work))
    (is (not (gi/active-owner-delegation? current root root)))
    (is (not (gi/active-owner-delegation? current other-root work)))
    (is (not (gi/active-owner-delegation? suspended root work)))
    (is (= root (gi/parent-of current work)))
    (is (= #{work codex}
           (set (map :actor/did (get-in (discover lg delegated) [:result :actors])))))
    (is (nil? (:error (send lg delegated fx/bob))))
    (is (= :forbidden (:error (send lg delegated fx/carol))))))
