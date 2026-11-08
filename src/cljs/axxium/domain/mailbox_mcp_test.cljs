(ns axxium.domain.mailbox-mcp-test
  "The eight acceptance tests of docs/addressable-mailbox-mcp-mvp.md
   (open-hax/axxium#47), written before the implementation."
  (:require [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.mailbox-mcp :as mm]
            [axxium.domain.policy :as policy]
            [axxium.extern.mailbox-mcp :as rpc]
            [axxium.shape.ledger-edn :as edn-lines]
            [cljs.test :refer [deftest is testing]]))

(def aud "axxium:test")
(def alice {:auth/did fx/alice :auth/method :atproto})
(def bob {:auth/did fx/bob :auth/method :service-token})
(def carol {:auth/did fx/carol :auth/method :atproto})
(def t-mid #inst "2026-11-01T00:30:00.000-00:00")
(def t-late #inst "2026-11-01T02:00:00.000-00:00")

(defn ctx
  ([p sid] (ctx p sid fx/t0))
  ([p sid now] {:principal p :session/id sid :audience aud :now now}))

(defn- ok [{:keys [ledger result error]}]
  (is (nil? error) (pr-str error))
  [ledger result])

(defn- open [lg p & [opts]]
  (ok (mm/call lg {:principal p :audience aud :now fx/t0} :open_session
               (merge {:audience aud :ttl-ms 3600000} opts))))

(defn world
  "Alice and Bob have mailboxes; Alice may send to Bob; Bob is a push subscriber."
  []
  (let [lg mm/empty-ledger
        [lg a] (open lg alice)
        [lg b] (open lg bob {:push true})
        [lg _] (ok (mm/call lg (ctx alice (:session/id a)) :register_mailbox {}))
        [lg _] (ok (mm/call lg (ctx bob (:session/id b)) :register_mailbox {}))
        [lg _] (ok (mm/call lg (ctx bob (:session/id b)) :grant_send {:to fx/alice}))]
    {:lg lg :a (:session/id a) :b (:session/id b)}))

(defn- send [lg c k body]
  (mm/call lg c :send_message {:to fx/bob :idempotency-key k :body body}))

(deftest registration-repairs-only-a-missing-initial-grant
  (let [[initial s] (open mm/empty-ledger alice)
        c (ctx alice (:session/id s))
        [registered] (ok (mm/call initial c :register_mailbox {}))]
    (doseq [length (range (count initial) (inc (count registered)))]
      (let [prefix (subvec registered 0 length)
            [repaired] (ok (mm/call prefix c :register_mailbox {}))]
        (is (nil? (:error (mm/call repaired c :read_inbox {})))
            (str "the mailbox is usable after retrying legacy prefix " length))
        (is (= repaired (:ledger (mm/call repaired c :register_mailbox {})))
            "repeating a complete registration adds no events")))
    (doseq [before [registered (subvec registered 0 (+ (count initial) 2))]]
      (let [[revoked] (ok (mm/call before c :revoke_send {:to fx/alice}))
            [retry] (ok (mm/call revoked c :register_mailbox {}))]
        (is (= retry (:ledger (mm/call retry c :register_mailbox {}))))
        (is (nil? (:error (mm/call retry c :read_inbox {}))))
        (is (= :forbidden (:error (mm/call retry c :send_message
                                           {:to fx/alice :idempotency-key "revoked" :body {:n 1}})))
            "retry preserves revocation even when the initial grant never committed")))))

;; 1
(deftest anonymous-and-cross-owner-refused
  (let [{:keys [lg a b]} (world)]
    (is (= :unauthenticated (:error (mm/call lg {:audience aud :now fx/t0} :read_inbox {}))))
    (is (= :unauthenticated (:error (send lg {:audience aud :now fx/t0} "k" {:text "hi"}))))
    (testing "alice may not read bob's mailbox"
      (is (= :forbidden (:error (mm/call lg (ctx alice a) :read_inbox {:mailbox fx/bob})))))
    (testing "bob may not write into a mailbox he holds no :send grant on"
      (is (= :forbidden (:error (mm/call lg (ctx bob b) :send_message
                                         {:to fx/alice :idempotency-key "x" :body {:text "?"}})))))
    (testing "refusals are recorded decisions"
      (let [r (mm/call lg (ctx alice a) :read_inbox {:mailbox fx/bob})]
        (is (policy/decision-before-effect? (:ledger r)))))))

;; 2
(deftest idempotent-send
  (let [{:keys [lg a]} (world)
        [lg1 r1] (ok (send lg (ctx alice a) "k1" {:text "hi"}))
        [lg2 r2] (ok (send lg1 (ctx alice a) "k1" {:text "hi"}))
        changed (send lg2 (ctx alice a) "k1" {:text "bye"})]
    (is (= (:message/id r1) (:message/id r2)))
    (is (seq (:message/receipt r1)))
    (is (= (:message/receipt r1) (:message/receipt r2)))
    (is (= 1 (count (mm/messages lg2 fx/bob))))
    (is (= :idempotency-conflict (:error changed)))
    (testing "the receipt is stored with the message, key, owner and recipient"
      (let [m (first (mm/messages lg1 fx/bob))]
        (is (= #{:message/id :message/from :message/to :message/body
                 :message/idempotency-key :message/owner :message/receipt}
               (set (keys m))))))))

;; 3
(deftest crash-between-commit-and-notify
  (let [{:keys [lg a]} (world)
        [committed _] (ok (send lg (ctx alice a) "k1" {:text "hi"}))
        restored (edn-lines/import-lines (edn-lines/export-lines committed))
        d1 (mm/dispatch restored fx/t0)
        d2 (mm/dispatch (:ledger d1) fx/t0)]
    (is (= committed restored))
    (is (= 1 (count (mm/messages restored fx/bob))))
    (is (= 1 (count (:notifications d1))))
    (is (empty? (:notifications d2)) "replay does not duplicate")
    (is (= 1 (count (mm/messages (:ledger d2) fx/bob))))))

;; 4
(deftest ack-reconnect-resume
  (let [{:keys [lg a b]} (world)
        [lg _] (ok (send lg (ctx alice a) "k1" {:n 1}))
        [lg _] (ok (send lg (ctx alice a) "k2" {:n 2}))
        [lg r] (ok (mm/call lg (ctx bob b) :read_inbox {}))
        first-id (:message/id (first (:messages r)))
        [lg _] (ok (mm/call lg (ctx bob b) :acknowledge_message {:message-id first-id}))
        restored (edn-lines/import-lines (edn-lines/export-lines lg))
        [lg2 s2] (open restored bob)
        [_ r2] (ok (mm/call lg2 (ctx bob (:session/id s2)) :read_inbox {}))]
    (is (= [{:n 1} {:n 2}] (mapv :message/body (:messages r))))
    (is (= [{:n 2}] (mapv :message/body (:messages r2))))
    (is (some? (:cursor r2)))))

;; 5
(deftest notification-is-not-completion
  (let [{:keys [lg a]} (world)
        [lg r] (ok (send lg (ctx alice a) "k1" {:text "do it"}))
        lg (:ledger (mm/dispatch lg fx/t0))
        [_ d] (ok (mm/call lg (ctx alice a) :get_delivery {:message-id (:message/id r)}))]
    (is (= :accepted (:delivery/accepted d)))
    (is (= :pending (:delivery/notification d)) "planning is not delivery")
    (is (= :push (:delivery/notification-plan d)))
    (is (false? (:delivery/acknowledged? d)))
    (is (= :unknown (:delivery/execution d)))
    (let [c (mm/confirm-delivery lg (:message/id r) {:at fx/t0})
          [_ d2] (ok (mm/call (:ledger c) (ctx alice a) :get_delivery {:message-id (:message/id r)}))]
      (is (= :delivered (:delivery/notification d2)) "only an external ack delivers")
      (is (= :unknown (:delivery/execution d2))))))

;; 6
(deftest fail-closed
  (let [{:keys [lg a b]} (world)]
    (testing "revoked grant"
      (let [[lg _] (ok (mm/call lg (ctx bob b) :revoke_send {:to fx/alice}))]
        (is (= :forbidden (:error (send lg (ctx alice a) "k" {:x 1}))))))
    (testing "wrong audience"
      (is (= :wrong-audience (:error (send lg (assoc (ctx alice a) :audience "axxium:prod") "k" {:x 1}))))
      (is (= :wrong-audience (:error (mm/call lg {:principal carol :audience aud :now fx/t0}
                                              :open_session {:audience "other" :ttl-ms 1000})))))
    (testing "expired session"
      (is (= :session-expired (:error (send lg (ctx alice a t-late) "k" {:x 1}))))
      (is (nil? (:error (send lg (ctx alice a t-mid) "k" {:x 1})))))
    (testing "forged sender ids"
      (is (= :forged-sender (:error (mm/call lg (ctx alice a) :send_message
                                             {:to fx/bob :from fx/carol :idempotency-key "k" :body {}}))))
      (is (= :session-mismatch (:error (send lg (ctx carol a) "k" {:x 1})))))
    (testing "secrets never enter payloads"
      (is (= :secret-in-payload (:error (send lg (ctx alice a) "k" {:token "abc"}))))
      (is (= :secret-in-payload (:error (send lg (ctx alice a) "k" {:note "Bearer abc.def"})))))))

;; 7
(deftest live-mcp-roundtrip
  (let [{:keys [lg a]} (world)
        invoke (fn [lg id method params]
              (rpc/handle-rpc lg (ctx alice a) {"jsonrpc" "2.0" "id" id "method" method "params" params}))
        {lg :ledger listed :response} (invoke lg 1 "tools/list" {})
        names (set (map #(get % "name") (get-in listed ["result" "tools"])))
        {lg :ledger sent :response} (invoke lg 2 "tools/call" {"name" "send_message"
                                                            "arguments" {"to" fx/bob "idempotency_key" "k" "body" {"text" "hi"}}})
        msg-id (get-in sent ["result" "structuredContent" "message_id"])
        {got :response} (invoke lg 3 "tools/call" {"name" "get_delivery" "arguments" {"message_id" msg-id}})]
    (is (= #{"whoami" "open_session" "resolve_address" "send_message" "read_inbox"
             "acknowledge_message" "get_delivery"}
           (set (filter mm/spec-tools names))))
    (is (string? msg-id))
    (is (= "accepted" (get-in got ["result" "structuredContent" "accepted"])))
    (is (= {"error" {"code" -32001 "message" "unauthenticated"} "id" 4 "jsonrpc" "2.0"}
           (:response (rpc/handle-rpc lg {:audience aud :now fx/t0}
                                     {"jsonrpc" "2.0" "id" 4 "method" "tools/list"}))))))

;; 8
(deftest push-only-for-subscribers
  (let [{:keys [lg b]} (world)
        [lg s] (open lg alice)
        _ (is (= :poll (:delivery/mode s)) "alice's client declared no push capability")
        [lg _] (ok (mm/call lg (ctx alice (:session/id s)) :grant_send {:to fx/bob}))
        [lg sent] (ok (mm/call lg (ctx bob b) :send_message {:to fx/alice :idempotency-key "k" :body {:x 1}}))
        d (mm/dispatch lg fx/t0)]
    (is (empty? (:notifications d)) "no push claimed for a polling client")
    (let [confirmed (mm/confirm-delivery (:ledger d) (:message/id sent) {:at fx/t0})
          [_ delivery] (ok (mm/call (:ledger confirmed) (ctx alice (:session/id s))
                                   :get_delivery {:message-id (:message/id sent)}))]
      (is (= :not-planned (:error confirmed)))
      (is (= (:ledger d) (:ledger confirmed)))
      (is (= :pending (:delivery/notification delivery))))
    (let [[_ r] (ok (mm/call (:ledger d) (ctx alice (:session/id s)) :read_inbox {}))]
      (is (= [{:x 1}] (mapv :message/body (:messages r)))))
    (testing "a subscribed client gets message.available"
      (let [{lg :lg a :a} (world)
            [lg _] (ok (send lg (ctx alice a) "k" {:x 1}))
            n (first (:notifications (mm/dispatch lg fx/t0)))]
        (is (= {:event "message.available" :recipient fx/bob} (select-keys n [:event :recipient])))
        (is (not (contains? n :message/body)) "notifications carry no payload")))))

(deftest notifications-are-silent-and-preserve-effects
  (let [{:keys [lg a]} (world)
        c (ctx alice a)]
    (doseq [method ["initialize" "tools/list" "notifications/initialized" "unknown"]]
      (let [out (rpc/handle-rpc lg c {"jsonrpc" "2.0" "method" method})]
        (is (nil? (:response out)) (str method " notification has no response"))
        (is (= lg (:ledger out)))))
    (let [out (rpc/handle-rpc lg c {"jsonrpc" "2.0" "method" "tools/call"
                                  "params" {"name" "send_message" "arguments"
                                            {"to" fx/bob "idempotency_key" "notification" "body" {"n" 1}}}})]
      (is (nil? (:response out)))
      (is (= 1 (count (mm/messages (:ledger out) fx/bob)))))
    (is (= -32601 (get-in (rpc/handle-rpc lg c {"jsonrpc" "2.0" "id" 0 "method" "unknown"})
                          [:response "error" "code"])))
    (is (nil? (:response (rpc/handle-rpc lg {} {"jsonrpc" "2.0" "method" "unknown"}))))))

(deftest tool-hints-and-actionable-errors
  (let [descriptors (into {} (map (juxt #(get % "name") identity)) rpc/tool-descriptors)
        {:keys [lg a]} (world)
        out (rpc/handle-rpc lg (ctx alice a)
                           {"jsonrpc" "2.0" "id" 1 "method" "tools/call"
                            "params" {"name" "unknown" "arguments" {}}})]
    (doseq [name ["whoami" "resolve_address" "get_delivery"]]
      (is (true? (get-in descriptors [name "annotations" "readOnlyHint"]))))
    (is (true? (get-in descriptors ["send_message" "annotations" "idempotentHint"])))
    (is (true? (get-in descriptors ["send_message" "annotations" "openWorldHint"]))
        "sending to another DID interacts with an external actor")
    (doseq [name ["grant_send" "revoke_send"]]
      (is (false? (get-in descriptors [name "annotations" "readOnlyHint"])))
      (is (true? (get-in descriptors [name "annotations" "destructiveHint"]))))
    (is (re-find #"tools/list" (get-in out [:response "result" "content" 0 "text"])))))

