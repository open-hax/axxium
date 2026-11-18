(ns axxium.law.codex-dispatch-test
  (:require [axxium.law.codex-dispatch :as dispatch]
            [cljs.test :refer [deftest is testing]]))

(def ^:private binding-id {:actor-did "did:web:fixture.example:actor"
                           :device-id "device-a"
                           :thread-id "thread-a"
                           :generation "gen-a"})

(def ^:private digest "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def ^:private other-digest "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(def ^:private intent {:binding binding-id :caller-id "notice-caller" :notice-sha256 digest})
(def ^:private queued-row {:caller-id "notice-caller" :notice-sha256 digest :queue-id "queue-a"})
(def ^:private completed-item {:caller-id "notice-caller" :notice-sha256 digest
                               :turn-id "turn-a" :item-id "item-a" :status :completed})

(def ^:private observed {:binding binding-id :queue-complete? true :history-complete? true
                         :queue [] :history []})

(deftest unique-positive-native-evidence
  (testing "a matching row records acceptance while retaining the intent"
    (is (= {:disposition :accepted :queue-id "queue-a"}
           (dispatch/reconcile intent (assoc observed :queue [queued-row]))))
    (is (= {:disposition :accepted :queue-id "queue-a"}
           (dispatch/reconcile (assoc intent :queue-id "queue-a")
                               (assoc observed :queue [queued-row])))))
  (testing "a matching completed native turn can retire an intent even when add returned no queue ID"
    (is (= {:disposition :retire :turn-id "turn-a" :item-id "item-a"}
           (dispatch/reconcile intent (assoc observed :history [completed-item]))))
    (is (= {:disposition :retire :turn-id "turn-a" :item-id "item-a"}
           (dispatch/reconcile (assoc intent :queue-id "queue-a")
                               (assoc observed
                                      :queue [(assoc queued-row :caller-id "unrelated" :queue-id "queue-b")]
                                      :history [completed-item]))))))

(deftest uncertain-observations-never-authorize-another-add
  (doseq [saved [intent (assoc intent :queue-id "queue-a")]]
    (is (= {:disposition :hold :reason :no-positive-evidence}
           (dispatch/reconcile saved observed))))
  (is (= {:disposition :hold :reason :no-positive-evidence}
         (dispatch/reconcile intent
                             (assoc observed :history [(assoc completed-item :caller-id "other-caller")]))))
  (doseq [status [:interrupted :in-progress :failed]]
    (is (= {:disposition :hold :reason :turn-not-completed}
           (dispatch/reconcile intent (assoc observed :history [(assoc completed-item :status status)])))))
  (is (= {:disposition :hold :reason :queue-and-history}
         (dispatch/reconcile intent (assoc observed :queue [queued-row] :history [completed-item]))))
  (is (= {:disposition :hold :reason :duplicate-caller}
         (dispatch/reconcile intent (assoc observed :queue [queued-row (assoc queued-row :queue-id "queue-b")]))))
  (is (= {:disposition :hold :reason :duplicate-caller}
         (dispatch/reconcile intent (assoc observed :history [completed-item
                                                            (assoc completed-item :turn-id "turn-b" :item-id "item-b")])))))

(deftest correlation-requires-matching-provenance
  (doseq [key [:actor-did :device-id :thread-id :generation]]
    (is (= {:disposition :hold :reason :binding-mismatch}
           (dispatch/reconcile intent (-> observed
                                          (assoc-in [:binding key] "another-binding")
                                          (assoc :history [completed-item]))))))
  (doseq [evidence [{:queue [(assoc queued-row :notice-sha256 other-digest)]}
                    {:history [(assoc completed-item :notice-sha256 other-digest)]}]]
    (is (= {:disposition :hold :reason :notice-mismatch}
           (dispatch/reconcile intent (merge observed evidence)))))
  (is (= {:disposition :hold :reason :queue-id-mismatch}
         (dispatch/reconcile (assoc intent :queue-id "queue-b") (assoc observed :queue [queued-row]))))
  (is (= {:disposition :hold :reason :queue-id-mismatch}
         (dispatch/reconcile (assoc intent :queue-id "queue-a")
                             (assoc observed :queue [(assoc queued-row :caller-id "unrelated")]
                                    :history [completed-item])))))

(deftest reject-incomplete-or-malformed-projections
  (doseq [key [:queue-complete? :history-complete?]]
    (is (= {:disposition :hold :reason :incomplete-observation}
           (dispatch/reconcile intent (assoc observed key false :history [completed-item])))))
  (doseq [projection [nil [queued-row queued-row] [(dissoc queued-row :queue-id)] ["not-a-row"]]]
    (is (= {:disposition :hold :reason :invalid-observation}
           (dispatch/reconcile intent (assoc observed :queue projection :history [completed-item])))))
  (doseq [history [[completed-item completed-item] [(assoc completed-item :status :unknown)]]]
    (is (= {:disposition :hold :reason :invalid-observation}
           (dispatch/reconcile intent (assoc observed :history history)))))
  (is (= {:disposition :hold :reason :invalid-observation}
         (dispatch/reconcile intent [:not-an-observation])))
  (doseq [saved [nil (assoc intent :caller-id "") (assoc intent :notice-sha256 "bad")
                 (assoc-in intent [:binding :generation] nil)]]
    (is (= {:disposition :hold :reason :invalid-intent}
           (dispatch/reconcile saved observed)))))
