(ns axxium.extern.pg-test
  "PostgreSQL transaction failure and error-identity boundary fixtures."
  (:require [axxium.extern.pg :as pg]
            [cljs.test :refer [async deftest is]]))

(deftest only-provider-subject-uniqueness-conflicts-qualify-for-recovery
  (is (pg/provider-binding-conflict? #js {:code "23505" :constraint "idx_provider_bindings_subject"}))
  (is (not (pg/provider-binding-conflict? #js {:code "23503" :constraint "idx_provider_bindings_subject"})))
  (is (not (pg/provider-binding-conflict? #js {:code "23505" :constraint "actors_pkey"})))
  (is (not (pg/provider-binding-conflict? (js/Error. "unrelated")))))

(defn- ^:async rollback-failure-discards-and-releases-client* []
  (let [commands (atom [])
        discarded (atom [])
        client #js {:query (fn [sql]
                             (swap! commands conj sql)
                             (if (= sql "ROLLBACK")
                               (js/Promise.reject (js/Error. "rollback unavailable"))
                               (js/Promise.resolve nil)))
                    :release (fn [discard?] (swap! discarded conj discard?))}
        pool #js {:connect #(js/Promise.resolve client)}]
    (is (= "operation failed"
           (try
             (await (pg/with-transaction! pool (fn [_] (js/Promise.reject (js/Error. "operation failed")))))
             (catch :default error (.-message error)))))
    (is (= ["BEGIN" "ROLLBACK"] @commands))
    (is (= [true] @discarded))))

;; A bare `^:async` deftest drops a rejected promise: assertions after the rejection
;; never run and the suite still exits 0. Wrap each in `async`/`done` and report throws.
(deftest rollback-failure-discards-and-releases-client
  (async done (-> (rollback-failure-discards-and-releases-client*) (.catch (fn [e] (is false (str "rollback-failure-discards-and-releases-client rejected: " (pr-str e))))) (.finally done))))

(deftest actor-email-conflict-is-exact
  (is (pg/actor-email-conflict? #js {:code "23505" :constraint "idx_actors_email"}))
  (is (not (pg/actor-email-conflict? #js {:code "23505" :constraint "idx_provider_bindings_subject"})))
  (is (not (pg/actor-email-conflict? #js {:code "23503" :constraint "idx_actors_email"}))))

(defn- ^:async shutdown-prevents-transaction-commit* []
  (let [commands (atom [])
        stopped (atom false)
        released (atom [])
        client #js {:query (fn [sql] (swap! commands conj sql) (js/Promise.resolve nil))
                    :release (fn [discard?] (swap! released conj discard?))}
        pool #js {:connect #(js/Promise.resolve client)}]
    (try
      (await (pg/with-transaction! pool
               (fn [_] (reset! stopped true) (js/Promise.resolve :value))
               #(deref stopped)))
      (is false "interrupted transaction must not commit")
      (catch :default _ nil))
    (is (= ["BEGIN" "ROLLBACK"] @commands))
    (is (= [false] @released))))

(deftest shutdown-prevents-transaction-commit
  (async done (-> (shutdown-prevents-transaction-commit*)
                  (.catch (fn [e] (is false (str "shutdown transaction test rejected: " (pr-str e)))))
                  (.finally done))))
