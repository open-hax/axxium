(ns axxium.extern.identity-ledger-test
  (:require [axxium.extern.ledger-file :as disk]
            [axxium.extern.ledger-queue :as queue]
            [cljs.test :refer [async deftest is]]))

(defn- ^:async identity-reloads-after-uncertain-append* []
  (let [writer (queue/create)
        durable (atom [])
        seen (atom nil)]
    (with-redefs [disk/load-ledger (fn [_] @durable)
                  disk/append-batch! (fn [_ _ after]
                                       (reset! durable after)
                                       (throw (js/Error. "fsync failed")))]
      (is (= [] (queue/current-ledger writer "identity-fixture")))
      (try
        (await (queue/submit! writer "identity-fixture"
                              (fn [_] {:ledger [:credential]})))
        (is false "the failed persistence must reject")
        (catch :default _))
      (is (= [:credential] (queue/current-ledger writer "identity-fixture"))
          "identity reads reload events that may have reached disk")
      (await (queue/submit! writer "identity-fixture"
                            (fn [before] (reset! seen before) {})))
      (is (= [:credential] @seen)
          "the next identity operation sees the same durable ledger"))))

(deftest identity-reloads-after-uncertain-append
  (async done
    (-> (identity-reloads-after-uncertain-append*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))
