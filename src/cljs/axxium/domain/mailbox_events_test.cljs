(ns axxium.domain.mailbox-events-test
  (:require [axxium.domain.authority :as authority]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.ledger :as ledger]
            [axxium.domain.mailbox :as mailbox]
            [axxium.domain.mailbox-events :as events]
            [axxium.domain.mailbox-mcp :as mailbox-mcp]
            [axxium.shape.ledger-edn :as edn]
            [cljs.test :refer [deftest is]]))

(def ^:private bob-id
  "sub_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
(def ^:private other-id
  "sub_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def ^:private t5 #inst "2026-11-01T00:00:05.000Z")
(def ^:private t15 #inst "2026-11-01T00:00:15.000Z")
(def ^:private t29 #inst "2026-11-01T00:00:29.000Z")
(def ^:private t30 #inst "2026-11-01T00:00:30.000Z")
(def ^:private expires #inst "2026-11-01T01:00:00.000Z")

(defn- authorized [lg owner]
  (first (ledger/emit lg
                      (authority/grant-event
                       {:id (fx/event-uuid (+ 100 (count lg))) :at fx/t0 :granter owner}
                       {:grant/id (str "read:" owner) :grant/actor owner
                        :grant/resource (mailbox/stream owner) :grant/actions [:read]}))))

(defn- subscribe-args [generation]
  {:id bob-id :owner fx/bob :generation (fx/event-uuid generation)
   :sealed "v1.iv.tag.ciphertext" :expires expires :at fx/t0})

(defn- accept [lg owner id]
  ;; Reproduce the complete legacy accepted line with no following pending
  ;; record, as after a torn tail. The message id is the accepted event id.
  (let [event-id (fx/event-uuid id)]
    (first (ledger/emit lg {:id event-id :stream (mailbox/stream owner)
                            :type :axxium.mailbox/accepted :actor fx/alice :at fx/t0
                            :body {:message/id event-id :message/to owner
                                   :message/from fx/alice :message/body {"private" true}
                                   :message/receipt {:receipt/status :accepted}}}))))

(defn- successful [{:keys [ledger error]}]
  (is (nil? error) (pr-str error))
  ledger)

(defn- subscribed []
  (successful (events/subscribe (authorized [] fx/bob) (subscribe-args 200))))

(defn- due [lg at]
  (first (events/due-deliveries lg at)))

(deftest activates-future-only-and-refresh-preserves-the-boundary
  (let [initial (-> [] (authorized fx/bob) (accept fx/bob 300))
        args (subscribe-args 200)
        lg (successful (events/subscribe initial args))
        after-accept (-> lg (accept fx/carol 301) (accept fx/bob 302))
        original (due after-accept fx/t0)
        refreshed (successful (events/subscribe after-accept
                                                (assoc (subscribe-args 201)
                                                       :sealed "v1.new.iv.tag")))
        replacement (due refreshed fx/t0)]
    (is (= 1 (:after (events/subscription lg fx/bob))))
    (is (= lg (:ledger (events/subscribe lg args))) "exact retry is idempotent")
    (is (empty? (events/due-deliveries lg fx/t0)) "existing messages stay polling-only")
    (is (= (fx/event-uuid 302) (:message-id replacement)))
    (is (= (:event-id original) (:event-id replacement)))
    (is (= (fx/event-uuid 201) (:generation replacement)))
    (is (not (contains? replacement :message/body)))
    (is (not (contains? replacement :sealed)))
    (is (= :delivery-not-due (:error (events/attempt refreshed original fx/t0))))
    (is (= :subscription-conflict
           (:error (events/subscribe lg (assoc (subscribe-args 201) :id other-id)))))
    (is (= :generation-conflict
           (:error (events/subscribe refreshed (subscribe-args 200)))))))

(deftest subscription-owner-expiry-and-grant-revocation
  (let [lg (-> (subscribed) (accept fx/bob 300))
        original (due lg fx/t0)
        revoked (first (ledger/emit lg
                                    (authority/revoke-event
                                     {:id (fx/event-uuid 301) :at fx/t0 :revoker fx/bob}
                                     {:revoke/id "test-read-revocation" :revoke/actor fx/bob
                                      :revoke/resource (mailbox/stream fx/bob)
                                      :revoke/actions [:read] :revoke/at fx/t0})))
        removed (successful (events/unsubscribe lg fx/bob bob-id fx/t0))
        reactivated (successful (events/subscribe removed (subscribe-args 201)))
        expired (successful (events/subscribe lg (assoc (subscribe-args 202)
                                                         :at expires :expires fx/t1)))]
    (is (= :forbidden (:error (events/subscribe [] (subscribe-args 200)))))
    (is (events/authorized? lg fx/bob fx/t0))
    (is (not (events/authorized? revoked fx/bob fx/t0)))
    (is (not (events/authorized? lg nil fx/t0)))
    (is (= :invalid-subscription
           (:error (events/subscribe lg (assoc (subscribe-args 201) :sealed "whsec_raw")))))
    (is (= :invalid-subscription
           (:error (events/subscribe lg (assoc (subscribe-args 201) :expires fx/t2)))))
    (is (= lg (:ledger (events/unsubscribe lg fx/alice bob-id fx/t0))))
    (is (= lg (:ledger (events/unsubscribe lg fx/bob other-id fx/t0))))
    (is (nil? (events/subscription removed fx/bob)))
    (is (= removed (:ledger (events/unsubscribe removed fx/bob bob-id fx/t0))))
    (is (nil? (due reactivated fx/t0)) "unsubscribe/recreate does not replay history")
    (is (nil? (due lg expires)))
    (is (nil? (due expired expires)) "new grant after expiry does not replay history")
    (is (nil? (due revoked fx/t0)))
    (is (= :delivery-not-due (:error (events/attempt revoked original fx/t0))))))

(deftest active-subscriptions-are-bounded
  (let [owners (mapv #(str "did:web:recipient-" % ".example") (range 17))
        ledger (reduce authorized [] owners)
        activate (fn [lg i]
                   (events/subscribe lg (assoc (subscribe-args (+ 200 i)) :owner (owners i))))
        full (reduce #(successful (activate %1 %2)) ledger (range 16))]
    (is (= :subscription-capacity (:error (activate full 16))))
    (let [without-first (successful (events/unsubscribe full (owners 0) bob-id fx/t0))]
      (is (nil? (:error (activate without-first 16)))))))

(deftest persisted-attempt-retries-after-a-crash-and-rejects-stale-completion
  (let [lg (-> (subscribed) (accept fx/bob 300))
        first-attempt (due lg fx/t0)
        stored (successful (events/attempt lg first-attempt fx/t0))
        restored (edn/import-lines (edn/export-lines stored))
        second-attempt (due restored t30)
        stored-again (successful (events/attempt restored second-attempt t30))
        completed (events/settle stored-again second-attempt 202 t30)
        delivered (peek (:ledger completed))]
    (is (= :delivery-not-current (:error (events/settle lg first-attempt 200 fx/t0))))
    (is (= :delivery-not-due (:error (events/attempt stored first-attempt fx/t0))))
    (is (nil? (due restored t29)))
    (is (= (:event-id first-attempt) (:event-id second-attempt)))
    (is (= 2 (:attempt second-attempt)))
    (is (= :delivery-not-current
           (:error (events/settle stored-again first-attempt 200 t30))))
    (is (= :delivered (get-in completed [:result :status])))
    (is (= :axxium.outbox/delivered (:event/type delivered)))
    (is (= 202 (get-in delivered [:event/body :outbox/transport-receipt :status])))
    (is (nil? (due (:ledger completed) t30)))
    (is (= :delivery-not-current
           (:error (events/settle (:ledger completed) second-attempt 202 t30))))))

(deftest transient-delivery-failures-have-durable-backoff-and-a-limit
  (let [lg (-> (subscribed) (accept fx/bob 300) (accept fx/bob 301))
        first-attempt (due lg fx/t0)
        attempt-one (successful (events/attempt lg first-attempt fx/t0))
        retry-one (events/settle attempt-one first-attempt 429 fx/t0 "req_retry")
        second-attempt (due (:ledger retry-one) t5)
        attempt-two (successful (events/attempt (:ledger retry-one) second-attempt t5))
        retry-two (events/settle attempt-two second-attempt nil t5 "req_no_http")
        third-attempt (due (:ledger retry-two) t15)
        attempt-three (successful (events/attempt (:ledger retry-two) third-attempt t15))
        terminal (events/settle attempt-three third-attempt 503 t15 "invalid, header")]
    (is (= :retry (get-in retry-one [:result :status])))
    (is (= "req_retry" (-> retry-one :ledger peek :event/body :outbox/response-request-id)))
    (is (= (fx/event-uuid 301) (:message-id (due (:ledger retry-one) fx/t0))))
    (is (= 2 (:attempt second-attempt)))
    (is (= :retry (get-in retry-two [:result :status])))
    (is (nil? (-> retry-two :ledger peek :event/body :outbox/response-request-id)))
    (is (= (fx/event-uuid 301) (:message-id (due (:ledger retry-two) t5))))
    (is (= 3 (:attempt third-attempt)))
    (is (= :terminal (get-in terminal [:result :status])))
    (is (nil? (-> terminal :ledger peek :event/body :outbox/response-request-id)))
    (is (= (fx/event-uuid 301) (:message-id (due (:ledger terminal) t30))))
    (is (= :delivery-not-current
           (:error (events/settle (:ledger terminal) third-attempt 200 t30))))))

(deftest terminal-and-historical-polling-never-claim-webhook-receipt
  (let [lg (-> (subscribed) (accept fx/bob 300))
        candidate (due lg fx/t0)
        attempted (successful (events/attempt lg candidate fx/t0))
        old-plan (first (ledger/emit lg
                                     {:id (fx/event-uuid 400) :stream "outbox"
                                      :type :axxium.outbox/dispatched :actor fx/bob :at fx/t0
                                      :causes [(fx/event-uuid 300)]
                                      :body {:outbox/message-id (fx/event-uuid 300)
                                             :outbox/channel :poll}}))]
    (doseq [status [302 401 410 413]]
      (let [settled (events/settle attempted candidate status fx/t0)]
        (is (= :terminal (get-in settled [:result :status])))
        (is (nil? (due (:ledger settled) t30)))
        (is (not-any? #(= :axxium.outbox/delivered (:event/type %)) (:ledger settled)))))
    (is (nil? (due old-plan fx/t0)))
    (is (= :delivery-not-due (:error (events/attempt old-plan candidate fx/t0))))
    (is (= :not-planned (:error (mailbox-mcp/confirm-delivery old-plan (fx/event-uuid 300) {:at fx/t0}))))))

(deftest refreshed-generation-cannot-receive-an-old-attempt-result
  (let [lg (-> (subscribed) (accept fx/bob 300))
        candidate (due lg fx/t0)
        attempted (successful (events/attempt lg candidate fx/t0))
        refreshed (successful (events/subscribe attempted (subscribe-args 201)))
        retry (due refreshed t30)]
    (is (= :delivery-not-current (:error (events/settle refreshed candidate 200 t5))))
    (let [removed (successful (events/unsubscribe attempted fx/bob bob-id fx/t0))]
      (is (= :delivery-not-current (:error (events/settle removed candidate 200 t5)))))
    (is (nil? (due refreshed t29)))
    (is (= 2 (:attempt retry)))
    (is (= (:event-id candidate) (:event-id retry)))
    (is (= (fx/event-uuid 201) (:generation retry)))))

(deftest mailbox-readback-distinguishes-webhook-receipt-from-acknowledgment
  (let [ctx {:principal {:auth/did fx/bob :auth/method :atproto}
             :audience "axxium:test" :now fx/t0}
        opened (mailbox-mcp/call (subscribed) ctx :open_session
                                 {:audience (:audience ctx) :ttl-ms 3600000})
        ctx (assoc ctx :session/id (get-in opened [:result :session/id]))
        lg (accept (successful opened) fx/bob 300)
        item (due lg fx/t0)
        attempted (successful (events/attempt lg item fx/t0))
        delivered (successful (events/settle attempted item 204 fx/t0))
        readback (fn [history] (:result (mailbox-mcp/call history ctx :get_delivery
                                                          {:message-id (:message-id item)})))]
    (is (= :pending (:delivery/notification (readback attempted))))
    (is (= :delivered (:delivery/notification (readback delivered))))
    (is (false? (:delivery/acknowledged? (readback delivered))))
    (is (= :unknown (:delivery/execution (readback delivered))))))
