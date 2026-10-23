(ns axxium.extern.oidc-store-test
  "Protocol persistence boundary fixtures; never contact a live identity store."
  (:require [axxium.extern.oidc-store :as store]
            [cljs.test :refer [async deftest is]]))

(defn- ^:async persistence-boundary* []
  (let [calls (atom [])
        consume-count (atom 0)
        pool #js {:query
                  (fn [sql params]
                    (swap! calls conj [sql (vec (array-seq params))])
                    (js/Promise.resolve
                      (cond
                        (.startsWith sql "SELECT")
                        #js {:rowCount 1 :rows #js [#js {:payload #js {:accountId "actor-a" :grantId "grant-a"}
                                                        :consumed_at nil}]}
                        (.startsWith sql "UPDATE")
                        #js {:rowCount (if (= 1 (swap! consume-count inc)) 1 0) :rows #js []}
                        :else #js {:rowCount 1 :rows #js []})))}
        factory (store/adapter-factory pool)
        adapter (factory "AuthorizationCode")]
    (await (.upsert adapter "opaque-code" #js {:accountId "actor-a" :grantId "grant-a"} 60))
    (is (= ["AuthorizationCode" "opaque-code" "{\"accountId\":\"actor-a\",\"grantId\":\"grant-a\"}" 60]
           (second (first @calls))))
    (is (= "actor-a" (.-accountId (await (.find adapter "opaque-code")))))
    (await (.consume adapter "opaque-code"))
    (is (= "invalid_grant"
           (try (await (.consume adapter "opaque-code")) nil
                (catch :default error (.-error error)))))
    (await (.revokeByGrantId adapter "grant-a"))
    (is (= ["grant-a"] (second (last @calls))))
    (is (.includes (first (last @calls)) "payload->>'grantId'=$1"))
    (let [before (count @calls)]
      (doseq [lifetime [nil 0 -1 0.5 "60" js/Infinity]]
        (is (= "Invalid OIDC store lifetime"
               (try (await (.upsert adapter "id" #js {} lifetime)) nil
                    (catch :default error (.-message error))))))
      (is (= before (count @calls))))))

(deftest persistence-boundary
  (async done
    (-> (persistence-boundary*)
        (.catch (fn [error] (is false (str "Persistence fixture rejected: " (.-message error)))))
        (.finally done))))
