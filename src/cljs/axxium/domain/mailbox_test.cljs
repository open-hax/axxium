(ns axxium.domain.mailbox-test
  "Laws 7, 8 and 10 for mbox/<did> streams."
  (:require [axxium.domain.authority :as authority]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.ledger :as ledger]
            [axxium.domain.mailbox :as mbox]
            [axxium.domain.policy :as policy]
            [axxium.domain.state :as state]
            [axxium.law.principal :as principal]
            [axxium.shape.ledger-edn :as edn-lines]
            [cljs.test :refer [deftest is testing]]))

(def bob-signed-in (principal/from-atproto-session fx/bob))
(def alice-service (principal/from-service-claims {"did" fx/alice}))

(def with-grant
  (first (ledger/emit ledger/empty-ledger
                      (authority/grant-event {:id (fx/event-uuid 1) :at fx/t0 :granter fx/alice}
                                             {:grant/id "g-bob-send" :grant/actor fx/bob
                                              :grant/resource (mbox/mailbox-resource fx/alice)
                                              :grant/actions [:send]}))))

(deftest principals
  (is (= {:auth/did fx/alice :auth/method :service-token} alice-service))
  (is (nil? (principal/from-service-claims {"did" "not-a-did"})))
  (is (nil? (principal/from-atproto-session nil))))

(deftest send-requires-authentication
  (is (thrown-with-msg? js/Error #"authenticated"
                        (mbox/send-message with-grant (state/fold with-grant)
                                           {:auth/did fx/bob :auth/method :password}
                                           fx/alice {:text "hi"}
                                           {:decision-id (fx/event-uuid 2) :message-id (fx/event-uuid 3) :at fx/t0}))))

(deftest granted-send-lands-in-recipient-stream
  (let [{:keys [ledger effect]} (mbox/send-message with-grant (state/fold with-grant)
                                                   bob-signed-in fx/alice {:text "hi"}
                                                   {:decision-id (fx/event-uuid 2) :message-id (fx/event-uuid 3)
                                                    :at fx/t0 :message/id "m1"})]
    (is (= (str "mbox/" fx/alice) (:event/stream effect)))
    (is (= 1 (:event/seq effect)))
    (is (= fx/bob (:event/actor effect)))
    (is (= {:msg/to fx/alice :msg/auth :atproto :message/body {:text "hi"} :message/id "m1"}
           (:event/body effect)))
    (is (policy/decision-before-effect? ledger))
    (testing "round trip"
      (is (= ledger (edn-lines/import-lines (edn-lines/export-lines ledger)))))))

(deftest ungranted-send-records-only-the-refusal
  (let [{:keys [ledger effect decision]}
        (mbox/send-message ledger/empty-ledger (state/fold ledger/empty-ledger)
                           bob-signed-in fx/alice {:text "hi"}
                           {:decision-id (fx/event-uuid 2) :message-id (fx/event-uuid 3) :at fx/t0})]
    (is (nil? effect))
    (is (false? (get-in decision [:event/body :decision/allow?])))
    (is (empty? (ledger/stream ledger (mbox/stream fx/alice))))))

(deftest owner-reads-own-mailbox-and-read-is-recorded
  (let [sent (:ledger (mbox/send-message with-grant (state/fold with-grant)
                                         bob-signed-in fx/alice {:text "hi"}
                                         {:decision-id (fx/event-uuid 2) :message-id (fx/event-uuid 3) :at fx/t0}))
        {:keys [ledger messages decision]} (mbox/read-mailbox sent (state/fold sent) alice-service
                                                              {:decision-id (fx/event-uuid 4) :at fx/t1})]
    (is (= 1 (count messages)))
    (is (= :rule (get-in decision [:event/body :decision/reason])))
    (is (= (inc (count sent)) (count ledger)))))
