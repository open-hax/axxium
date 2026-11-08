(ns axxium.extern.ledger-queue-test
  (:require [axxium.extern.ledger-file :as disk]
            [axxium.extern.ledger-queue :as queue]
            [cljs.test :refer [async deftest is]]))

(defn- ^:async uncertain-write* []
  (let [writer (queue/create) durable (atom []) seen (atom nil)]
    (with-redefs [disk/load-ledger (fn [_] @durable)
                  disk/append-batch! (fn [_ _ after]
                                       (reset! durable after)
                                       (throw (js/Error. "fsync failed")))]
      (try
        (await (queue/submit! writer "fixture" (fn [_] {:ledger [:committed]})))
        (is false "append must reject")
        (catch :default _))
      (await (queue/submit! writer "fixture" (fn [before] (reset! seen before) {})))
      (is (= [:committed] @seen) "next operation reloads events written before failure"))))

(deftest reloads-after-uncertain-append
  (async done
    (-> (uncertain-write*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))
