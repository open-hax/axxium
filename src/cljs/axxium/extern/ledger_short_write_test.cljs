(ns axxium.extern.ledger-short-write-test
  (:require [axxium.extern.ledger-file :as disk]
            [cljs.test :refer [deftest is]]))

(deftest writes-every-byte-before-return
  (let [buffer (js/Buffer.from "aλb" "utf8")
        written (atom [])
        calls (atom 0)]
    (disk/write-all! buffer
                     (fn [buf offset length]
                       (swap! calls inc)
                       (let [n (min 2 length)]
                         (swap! written into (array-seq (.subarray buf offset (+ offset n))))
                         n)))
    (is (= (vec (array-seq buffer)) @written))
    (is (> @calls 1))))

(deftest refuses-a-non-progressing-write
  (let [calls (atom 0)
        error (try
                (disk/write-all! (js/Buffer.from "event" "utf8")
                                 (fn [_ _ _]
                                   (when (> (swap! calls inc) 1)
                                     (throw (js/Error. "test writer called again after zero progress")))
                                   0))
                (catch js/Error error error))]
    (is (= "ledger write made invalid progress" (ex-message error)))
    (is (= 1 @calls))))
