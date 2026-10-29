(ns axxium.config-test
  (:require [axxium.config :as config]
            [axxium.db :as db]
            [axxium.extern.lifecycle :as lifecycle]
            [axxium.extern.pg :as pg]
            [cljs.test :refer [deftest is]]))

(deftest schema-timeout-is-explicit-and-bounded
  (is (= 20000 (config/parse-schema-statement-timeout-ms "20000")))
  (is (= 120000 (config/parse-schema-statement-timeout-ms "120000")))
  (doseq [invalid ["" "0" "999" "300001" "20ms" "-1" "1e4"]]
    (is (thrown? js/Error (config/parse-schema-statement-timeout-ms invalid)))))

(deftest schema-pool-has-only-its-own-statement-timeout
  (let [seen (atom nil)]
    (with-redefs [pg/create-pool! (fn [options] (reset! seen options) :schema-pool)]
      (is (= :schema-pool (db/create-schema-pool! 120000))))
    (is (= 120000 (:statement-timeout-ms @seen)))
    (is (= 1 (:max @seen)))))

(deftest shutdown-bound-tracks-schema-timeout-before-readiness
  (is (= 25000 (lifecycle/shutdown-bound-ms false 20000)))
  (is (= 125000 (lifecycle/shutdown-bound-ms false 120000)))
  (is (= 25000 (lifecycle/shutdown-bound-ms true 120000))))
