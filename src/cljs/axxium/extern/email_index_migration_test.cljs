(ns axxium.extern.email-index-migration-test
  "Regressions for riatzukiza/axxium#8 Codex findings on the actors.email
   unique-index migration: the duplicate preflight must run under a table
   lock inside the migration transaction, and the uniqueness lookup must be
   scoped to the application's actors table."
  (:require [axxium.db :as db]
            [cljs.test :refer [async deftest is]]
            [clojure.string :as str]))

(defn- result [rows]
  #js {:rows (clj->js rows) :rowCount (count rows)})

(defn- index-lookup? [sql] (str/includes? sql "indisunique"))
(defn- duplicate-scan? [sql] (str/starts-with? sql "SELECT email"))
(defn- lock? [sql] (str/starts-with? sql "LOCK TABLE actors"))
(defn- drop? [sql] (str/starts-with? sql "DROP INDEX"))
(defn- create-unique? [sql] (str/starts-with? sql "CREATE UNIQUE INDEX IF NOT EXISTS idx_actors_email"))

(defn- schema-db
  "Fake pool. index-rows answers the scoped index lookup; duplicates answers
   the duplicate scan. Pool-direct and transaction-client SQL are logged apart."
  [{:keys [index-rows duplicates]}]
  (let [direct (atom []) tx (atom [])
        answer (fn [log sql]
                 (swap! log conj sql)
                 (js/Promise.resolve
                  (result (cond (index-lookup? sql) index-rows
                                (duplicate-scan? sql) duplicates
                                :else []))))
        client #js {:release (fn [_] nil) :query (fn [sql _] (answer tx sql))}]
    {:pool #js {:query (fn [sql _] (answer direct sql))
                :connect #(js/Promise.resolve client)}
     :direct direct :tx tx}))

(defn- index-of [pred xs] (first (keep-indexed (fn [i x] (when (pred x) i)) xs)))

(defn- ^:async legacy-index-swaps-under-lock-in-transaction*
  []
  (let [fixture (schema-db {:index-rows [{:is_unique false :qualified_name "public.idx_actors_email"}]
                            :duplicates []})]
    (with-redefs [db/pool (delay (:pool fixture))]
      (await (db/init-schema!)))
    (let [tx @(:tx fixture) direct @(:direct fixture)]
      (is (= "BEGIN" (first tx)))
      (is (= "COMMIT" (last tx)))
      (is (< (index-of lock? tx) (index-of duplicate-scan? tx) (index-of drop? tx) (index-of create-unique? tx))
          "lock, then duplicate scan, then drop and create, all in one transaction")
      (is (= "DROP INDEX IF EXISTS public.idx_actors_email" (nth tx (index-of drop? tx)))
          "drop targets the actors table's own index")
      (is (not-any? duplicate-scan? direct) "duplicate preflight must not run outside the transaction")
      (is (not-any? drop? direct)))))

(defn- ^:async duplicates-roll-back-without-dropping*
  []
  (let [fixture (schema-db {:index-rows [{:is_unique false :qualified_name "public.idx_actors_email"}]
                            :duplicates [{:email "a@b.test" :n 2}]})
        outcome (with-redefs [db/pool (delay (:pool fixture))]
                  (try (await (db/init-schema!)) :migrated
                       (catch :default _ :refused)))
        tx @(:tx fixture)]
    (is (= :refused outcome))
    (is (some #{"ROLLBACK"} tx))
    (is (not-any? #{"COMMIT"} tx))
    (is (not-any? drop? tx) "legacy index must survive a refused migration")))

(defn- ^:async unique-index-skips-locking*
  []
  (let [fixture (schema-db {:index-rows [{:is_unique true :qualified_name "public.idx_actors_email"}]})]
    (with-redefs [db/pool (delay (:pool fixture))]
      (await (db/init-schema!)))
    (is (empty? @(:tx fixture)) "already-unique index opens no transaction and takes no lock")
    (is (some #(and (index-lookup? %) (str/includes? % "indrelid = to_regclass('actors')"))
              @(:direct fixture))
        "uniqueness lookup is scoped to the application's actors table")))

(defn- ^:async absent-index-creates-without-drop*
  []
  (let [fixture (schema-db {:index-rows [] :duplicates []})]
    (with-redefs [db/pool (delay (:pool fixture))]
      (await (db/init-schema!)))
    (let [tx @(:tx fixture)]
      (is (some lock? tx))
      (is (not-any? drop? tx))
      (is (some create-unique? tx)))))

(deftest legacy-index-swaps-under-lock-in-transaction
  (async done (-> (legacy-index-swaps-under-lock-in-transaction*)
                  (.catch (fn [e] (is false (str "legacy-index-swaps-under-lock-in-transaction rejected: " (pr-str e)))))
                  (.finally done))))

(deftest duplicates-roll-back-without-dropping
  (async done (-> (duplicates-roll-back-without-dropping*)
                  (.catch (fn [e] (is false (str "duplicates-roll-back-without-dropping rejected: " (pr-str e)))))
                  (.finally done))))

(deftest unique-index-skips-locking
  (async done (-> (unique-index-skips-locking*)
                  (.catch (fn [e] (is false (str "unique-index-skips-locking rejected: " (pr-str e)))))
                  (.finally done))))

(deftest absent-index-creates-without-drop
  (async done (-> (absent-index-creates-without-drop*)
                  (.catch (fn [e] (is false (str "absent-index-creates-without-drop rejected: " (pr-str e)))))
                  (.finally done))))
