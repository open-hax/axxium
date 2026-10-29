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

(defn- ^:async durable-polling-boundary* []
  (let [context (atom {:poll? true :authenticated-client-id "registered-a"})
        row (atom {:payload {:clientId "registered-a" :kind "DeviceCode"}
                   :consumed_at nil :poll_last_ms nil :poll_interval 5 :poll_count 0
                   :expiry_ms "600000" :now_ms "1000"})
        queries (atom [])
        releases (atom 0)
        query (fn [sql params]
                (swap! queries conj sql)
                (js/Promise.resolve
                  (cond
                    (.startsWith sql "SELECT")
                    #js {:rowCount (if @row 1 0) :rows (clj->js (if @row [@row] []))}
                    (.startsWith sql "UPDATE axxium_oidc_records SET poll_last_ms")
                    (do (swap! row assoc :poll_last_ms (aget params 1)
                               :poll_interval (aget params 2) :poll_count (aget params 3))
                        #js {:rowCount 1 :rows #js []})
                    :else #js {:rowCount 0 :rows #js []})))
        client #js {:query query :release (fn [_discard] (swap! releases inc))}
        pool #js {:query query :connect (fn [] (js/Promise.resolve client))}
        adapter ((store/adapter-factory pool #(deref context)) "DeviceCode")
        find-error (fn ^:async find-error* []
                     (try (await (.find adapter "opaque-device")) nil
                          (catch :default error (.-error error))))]
    (is (= [:found "registered-a"]
           (try [:found (.-clientId (await (.find adapter "opaque-device")))]
                (catch :default _ [:query-failed]))))
    (is (= 1 (:poll_count @row)))
    (is (some #(.includes % "FOR UPDATE") @queries))
    (is (= "COMMIT" (last @queries)))
    (is (= "slow_down" (await (find-error))))
    (is (= 10 (:poll_interval @row)))
    (is (= 2 (:poll_count @row)))
    (is (= "COMMIT" (last @queries)) "Backoff is committed before throwing the OAuth error")
    (is (= "slow_down" (await (find-error))))
    (is (= 15 (:poll_interval @row)))
    (let [before @row]
      (reset! context {:poll? true :authenticated-client-id "wrong-client"})
      (is (= "invalid_grant" (await (find-error))))
      (is (= before @row))
      (reset! context {:poll? true :authenticated-client-id nil})
      (is (= "invalid_grant" (await (find-error))))
      (is (= before @row)))
    (reset! context {:poll? true :authenticated-client-id "registered-a"})
    (swap! row assoc :now_ms "16000")
    (is (= :found
           (try (if (some? (await (.find adapter "opaque-device"))) :found :missing)
                (catch :default _ :query-failed))))
    (is (= 4 (:poll_count @row)))
    (swap! row assoc :now_ms "600000")
    (is (= "expired_token" (await (find-error))))
    (is (= 4 (:poll_count @row)))
    (swap! row assoc :now_ms "20000" :consumed_at 1)
    (is (= "invalid_grant" (await (find-error))))
    (swap! row assoc :consumed_at nil :poll_count 120)
    (is (= "invalid_grant" (await (find-error))))
    (reset! row nil)
    (is (= "invalid_grant" (await (find-error))))
    (reset! context nil)
    (let [before @releases]
      (is (nil? (await (.find adapter "browser-device"))))
      (is (= before @releases) "Browser verification does not acquire the polling transaction")
      (is (.includes (last @queries) "expires_at>clock_timestamp()")))
    (is (= "Device authorization lifetime exceeds ten minutes"
           (try (await (.upsert adapter "too-long" #js {} 601)) nil
                (catch :default error (.-message error)))))))

(deftest durable-polling-boundary
  (async done
    (-> (durable-polling-boundary*)
        (.catch (fn [error] (is false (str "Device polling fixture rejected: " (.-message error)))))
        (.finally done))))

(defn- ^:async provider-lifetime-boundaries* []
  (let [calls (atom [])
        pool #js {:query (fn [sql params]
                           (swap! calls conj [sql (vec (array-seq params))])
                           (js/Promise.resolve #js {:rowCount 1 :rows #js []}))}
        adapter ((store/adapter-factory pool) "Session")
        year-seconds (* 365 24 60 60)]
    ;; Public upserts must preserve each admitted lifetime in the SQL parameters,
    ;; including the longest permitted provider record rather than rounding it.
    (doseq [lifetime [1 year-seconds]]
      (reset! calls [])
      (is (= :accepted
             (try (await (.upsert adapter "opaque-session" #js {} lifetime)) :accepted
                  (catch :default error [:rejected (.-message error)]))))
      (is (= 1 (count @calls)))
      (is (= ["Session" "opaque-session" "{}" lifetime] (second (first @calls)))))
    (doseq [lifetime [0 -1 0.5 (inc year-seconds) nil "1" js/NaN js/Infinity]]
      (reset! calls [])
      (is (= "Invalid OIDC store lifetime"
             (try (await (.upsert adapter "opaque-session" #js {} lifetime)) :accepted
                  (catch :default error (.-message error)))))
      (is (empty? @calls) "Malformed or excessive lifetimes must refuse before SQL"))))

(deftest provider-lifetime-boundaries
  (async done
    (-> (provider-lifetime-boundaries*)
        (.catch (fn [error] (is false (str "Provider lifetime fixture rejected: " (.-message error)))))
        (.finally done))))

(defn- ^:async opaque-key-public-boundaries* []
  (let [calls (atom [])
        pool #js {:query (fn [sql params]
                           (swap! calls conj [sql (vec (array-seq params))])
                           (js/Promise.resolve
                             #js {:rowCount 1 :rows #js [#js {:payload #js {} :consumed_at nil
                                                              :active true :expires_ms "10000"}]}))}
        factory (store/adapter-factory pool)
        adapter (factory "Session")
        digest (apply str (repeat 64 "a"))
        operations [["upsert" #(.upsert adapter % #js {} 60)]
                    ["find" #(.find adapter %)]
                    ["findByUserCode" #(.findByUserCode adapter %)]
                    ["findByUid" #(.findByUid adapter %)]
                    ["destroy" #(.destroy adapter %)]
                    ["revokeByGrantId" #(.revokeByGrantId adapter %)]
                    ["consume" #(.consume adapter %)]
                    ["bind grant" #(store/bind-session! pool % "actor" digest)]
                    ["bind actor" #(store/bind-session! pool "grant" % digest)]
                    ["active grant" #(store/session-grant-active? pool % "actor")]
                    ["active actor" #(store/session-grant-active? pool "grant" %)]
                    ["expiry grant" #(store/session-grant-expiry! pool % "actor")]
                    ["expiry actor" #(store/session-grant-expiry! pool "grant" %)]
                    ["revoke family" #(store/revoke-family! pool %)]]]
    (doseq [key ["k" (apply str (repeat 2048 "k"))]]
      (is (object? (factory key)) "The public constructor admits bounded opaque model keys")
      (doseq [[label operation] operations]
        (reset! calls [])
        (is (= :accepted
               (try (await (operation key)) :accepted
                    (catch :default error [:rejected (.-message error)]))) label)
        (is (= 1 (count @calls)) label)
        (is (some #(some #{key} (second %)) @calls)
            (str label " passes the opaque key as a parameter without truncation"))))
    (doseq [key [nil "" 42 #js {} #js [] (apply str (repeat 2049 "k"))]]
      (reset! calls [])
      (is (= "Invalid OIDC store identifier"
             (try (factory key) :accepted
                  (catch :default error (.-message error)))))
      (is (empty? @calls))
      (doseq [[label operation] operations]
        (reset! calls [])
        (is (= "Invalid OIDC store identifier"
               (try (await (operation key)) :accepted
                    (catch :default error (.-message error)))) label)
        (is (empty? @calls) (str label " refuses malformed keys before SQL"))))))

(deftest opaque-key-public-boundaries
  (async done
    (-> (opaque-key-public-boundaries*)
        (.catch (fn [error] (is false (str "Opaque key fixture rejected: " (.-message error)))))
        (.finally done))))
