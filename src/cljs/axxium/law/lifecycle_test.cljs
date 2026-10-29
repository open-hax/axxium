(ns axxium.law.lifecycle-test
  (:require [axxium.law.lifecycle :as law]
            [cljs.test :refer [deftest is]]))

(deftest installation-contract
  (let [valid {:runtime-pool-close? true
               :schema-pool-close? true
               :statement-timeout-ms 20000}]
    (doseq [timeout [1000 20000 300000]]
      (let [installation (assoc valid :statement-timeout-ms timeout)]
        (is (= installation (law/require-installation! installation)))))
    (doseq [invalid [(assoc valid :runtime-pool-close? false)
                     (assoc valid :schema-pool-close? false)
                     (dissoc valid :schema-pool-close?)
                     (assoc valid :statement-timeout-ms 999)
                     (assoc valid :statement-timeout-ms 300001)
                     (assoc valid :statement-timeout-ms 1000.5)
                     (assoc valid :statement-timeout-ms "20000")
                     (assoc valid :extra true)]]
      (is (thrown? cljs.core/ExceptionInfo
                   (law/require-installation! invalid))))))
