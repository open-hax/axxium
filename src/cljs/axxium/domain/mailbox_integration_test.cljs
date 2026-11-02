(ns axxium.domain.mailbox-integration-test
  "Cross-repository acceptance probes; see docs/mailbox-integration-regressions.md."
  (:require [axxium.domain.kernel-fixture :as fixture]
            [axxium.domain.mailbox-mcp :as mailbox]
            [cljs.test :refer [deftest is]]))

(def ^:private audience "promethean+testing")
(def ^:private alice {:auth/did fixture/alice :auth/method :atproto})
(def ^:private bob {:auth/did fixture/bob :auth/method :service-token})

(defn- context
  "Bind a fixture actor to its explicit application session."
  [principal session-id]
  {:principal principal :session/id session-id :audience audience :now fixture/t0})

(defn- successful
  "Refuse a broken fixture instead of asserting against missing results."
  [operation]
  (when (:error operation)
    (throw (ex-info "Mailbox integration fixture refused" {:error (:error operation)})))
  operation)

(defn- open-session
  "Enroll a fixture actor without external authentication or network effects."
  [ledger principal push?]
  (successful
   (mailbox/call ledger (context principal nil) :open_session
                 {:audience audience :ttl-ms 3600000 :push push?})))

(defn- world
  "Create two mailboxes and grant Alice permission to send to Bob."
  []
  (let [opened-a (open-session mailbox/empty-ledger alice false)
        opened-b (open-session (:ledger opened-a) bob true)
        ctx-a (context alice (get-in opened-a [:result :session/id]))
        ctx-b (context bob (get-in opened-b [:result :session/id]))
        registered-a (successful (mailbox/call (:ledger opened-b) ctx-a :register_mailbox {}))
        registered-b (successful (mailbox/call (:ledger registered-a) ctx-b :register_mailbox {}))
        granted (successful (mailbox/call (:ledger registered-b) ctx-b :grant_send
                                         {:to fixture/alice}))]
    {:ledger (:ledger granted) :alice ctx-a :bob ctx-b}))

(defn- send-message
  "Send one fixture body with an explicit idempotency key."
  [ledger ctx key body]
  (successful (mailbox/call ledger ctx :send_message
                            {:to fixture/bob :idempotency-key key :body body})))

(defn- rpc-tool
  "Exercise the public wire adapter with JSON-compatible string-keyed data."
  [ledger ctx id tool arguments]
  (mailbox/handle-rpc ledger ctx
                      {"jsonrpc" "2.0" "id" id "method" "tools/call"
                       "params" {"name" tool "arguments" arguments}}))

(deftest acknowledgment-of-second-message-preserves-first
  (let [{initial :ledger ctx-a :alice ctx-b :bob} (world)
        first-send (send-message initial ctx-a "first" {:ordinal 1})
        second-send (send-message (:ledger first-send) ctx-a "second" {:ordinal 2})
        acked (successful
               (mailbox/call (:ledger second-send) ctx-b :acknowledge_message
                             {:message-id (get-in second-send [:result :message/id])}))
        inbox (successful (mailbox/call (:ledger acked) ctx-b :read_inbox {}))
        ids (set (map :message/id (get-in inbox [:result :messages])))]
    (is (contains? ids (get-in first-send [:result :message/id]))
        "Acknowledging B must not silently acknowledge unread A.")))

(deftest json-message-body-retains-exact-user-keys
  (let [{initial :ledger ctx-a :alice ctx-b :bob} (world)
        body {"a/id" "alpha" "b/id" "beta" "x_y" 1 "x-y" 2
              "nested" [{"a/id" "nested-alpha" "b/id" "nested-beta"}]}
        sent (rpc-tool initial ctx-a 1 "send_message"
                       {"to" fixture/bob "idempotency_key" "json-keys" "body" body})
        inbox (rpc-tool (:ledger sent) ctx-b 2 "read_inbox" {})
        returned (get-in inbox [:response "result" "structuredContent"
                               "messages" 0 "message_body"])]
    (is (not (true? (get-in sent [:response "result" "isError"]))))
    (is (not (true? (get-in inbox [:response "result" "isError"]))))
    (is (= body returned) "Envelope key normalization must not rewrite the payload.")))

(deftest planning-notification-does-not-acknowledge-external-delivery
  (let [{initial :ledger ctx-a :alice} (world)
        sent (send-message initial ctx-a "outbox" {:text "fixture"})
        planned (mailbox/dispatch (:ledger sent) fixture/t0)
        delivery (successful
                  (mailbox/call (:ledger planned) ctx-a :get_delivery
                                {:message-id (get-in sent [:result :message/id])}))]
    (is (seq (:notifications planned)) "Positive control: a notification was planned.")
    (is (= :pending (get-in delivery [:result :delivery/notification]))
        "No transport was called and no external receipt was supplied.")))

(deftest replay-returns-the-actual-stored-receipt
  (let [{initial :ledger ctx-a :alice} (world)
        sent (send-message initial ctx-a "receipt" {:text "fixture"})
        replayed (send-message (:ledger sent) ctx-a "receipt" {:text "fixture"})
        receipt (get-in sent [:result :message/receipt])]
    (is (seq receipt) "A missing receipt is not successful idempotency evidence.")
    (is (= receipt (get-in replayed [:result :message/receipt])))
    (is (= (get-in sent [:result :message/id]) (get-in replayed [:result :message/id])))
    (is (= 1 (count (mailbox/messages (:ledger replayed) fixture/bob))))))
