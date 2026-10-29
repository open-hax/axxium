(ns axxium.config-test
  (:require [axxium.config :as config]
            [axxium.db :as db]
            [axxium.extern.lifecycle :as lifecycle]
            [axxium.extern.pg :as pg]
            [cljs.test :refer [deftest is]]))

(deftest schema-timeout-is-explicit-and-bounded
  (doseq [[input expected] [["1000" 1000] ["20000" 20000]
                            ["300000" 300000] ["120000" 120000]]]
    ;; Valid configuration must be accepted, including both interval bounds.
    (is (= [:accepted expected]
           (try [:accepted (config/parse-schema-statement-timeout-ms input)]
                (catch :default _ [:refused])))))
  (doseq [invalid ["" "0" "999" "300001" "20ms" "-1" "1e4"]]
    (is (thrown? js/Error (config/parse-schema-statement-timeout-ms invalid)))))

(deftest schema-pool-has-only-its-own-statement-timeout
  (let [seen (atom nil)]
    (with-redefs [pg/create-pool! (fn [options] (reset! seen options) :schema-pool)]
      (is (= :schema-pool (db/create-schema-pool! 120000))))
    (is (= 120000 (:statement-timeout-ms @seen)))
    (is (= 1 (:max @seen)))))

(deftest shutdown-bound-tracks-schema-timeout-before-readiness
  (is (= 45000 (lifecycle/shutdown-bound-ms false 20000)))
  (is (= 245000 (lifecycle/shutdown-bound-ms false 120000)))
  (is (= 25000 (lifecycle/shutdown-bound-ms true 120000))))
