(ns axxium.law.device-polling-test
  "Device polling admission must not rely on browser cookies or Origin."
  (:require [axxium.law.device-polling :as law]
            [cljs.test :refer [deftest is testing]]))

(def pending
  "A synthetic durable polling record; contains no raw device secret."
  {:client-id "yoga-knoxx" :expires-at-ms 600000 :last-poll-ms nil
   :interval-seconds 5 :consumed? false})

(deftest initial-poll-does-not-require-browser-state
  (let [result (law/admit-poll pending "yoga-knoxx" 1000)]
    (is (= :continue (:decision result)))
    (is (= 1000 (get-in result [:record :last-poll-ms])))
    (is (= 5 (get-in result [:record :interval-seconds])))))

(deftest polling-obeys-the-minimum-interval-and-backoff
  (let [record (assoc pending :last-poll-ms 1000)
        too-early (law/admit-poll record "yoga-knoxx" 5999)]
    (is (= :slow-down (:error too-early)))
    (is (= 10 (get-in too-early [:record :interval-seconds])))
    (is (= 5999 (get-in too-early [:record :last-poll-ms])))
    (is (= :continue (:decision (law/admit-poll record "yoga-knoxx" 6000))))
    (is (= :slow-down (:error (law/admit-poll (:record too-early) "yoga-knoxx" 10999))))
    (is (= :continue (:decision (law/admit-poll (:record too-early) "yoga-knoxx" 15999))))))

(deftest expired-consumed-and-wrong-client-grants-refuse-without-mutation
  (doseq [[record client now error]
          [[pending "other-client" 1000 :invalid-grant]
           [(assoc pending :consumed? true) "yoga-knoxx" 1000 :invalid-grant]
           [pending "yoga-knoxx" 600000 :expired-token]
           [pending "yoga-knoxx" 600001 :expired-token]]]
    (let [result (law/admit-poll record client now)]
      (is (= :refuse (:decision result)))
      (is (= error (:error result)))
      (is (not (contains? result :record))))))

(deftest malformed-input-and-clock-regression-fail-closed
  (testing "a missing or malformed store record is not a valid polling grant"
    (doseq [record [nil {} (dissoc pending :client-id)
                    (assoc pending :interval-seconds 0)
                    (assoc pending :consumed? "false")]]
      (is (= :invalid-grant (:error (law/admit-poll record "yoga-knoxx" 1000))))))
  (testing "clock evidence must be a finite non-negative integer"
    (doseq [now [nil -1 "1000" 0.5 js/NaN js/Infinity]]
      (is (= :invalid-request (:error (law/admit-poll pending "yoga-knoxx" now))))))
  (testing "a backwards clock cannot bypass polling backoff"
    (let [result (law/admit-poll (assoc pending :last-poll-ms 5000)
                                 "yoga-knoxx" 4000)]
      (is (= :slow-down (:error result)))
      (is (= 5000 (get-in result [:record :last-poll-ms])))))
  (testing "backoff never proposes an out-of-contract interval"
    (let [result (law/admit-poll (assoc pending :last-poll-ms 1000
                                      :interval-seconds 86400)
                                 "yoga-knoxx" 1001)]
      (is (= :invalid-grant (:error result)))
      (is (not (contains? result :record))))))
