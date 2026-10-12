(ns axxium.extern.pg
  "Thin extern wrapper around the `pg` npm Pool.
   Following knoxx.backend.extern.pg patterns."
  (:require ["pg" :as pg-lib]))

(defn create-pool!
  "Construct a PostgreSQL pool from the named runtime connection options."
  [{:keys [connection-string max idle-timeout-ms connect-timeout-ms]}]
  (new (.-Pool pg-lib)
       (clj->js {:connectionString connection-string
                   :max (or max 20)
                   :idleTimeoutMillis (or idle-timeout-ms 30000)
                   :connectionTimeoutMillis (or connect-timeout-ms 2000)})))

(defn- keywordize-rows
  [result]
  (let [r (if (.isArray js/Array result)
             (aget result (dec (.-length result)))
             result)]
    {:rows (mapv #(js->clj % :keywordize-keys true) (array-seq (.-rows r)))
     :row-count (.-rowCount r)}))

(defn query!
  "Execute parameterized SQL against pool-or-client.
   Returns Promise<{:rows [keywordized-CLJS-maps] :row-count N}>."
  [conn sql-str params]
  (let [params-arr (when (seq params) (into-array params))]
    (-> (.query conn sql-str params-arr)
        (.then keywordize-rows))))

(defn query-one!
  "Execute SQL and return Promise<first-row-as-CLJS-map | nil>."
  [conn sql-str params]
  (-> (query! conn sql-str params)
      (.then (fn [{:keys [rows]}] (first rows)))))

(defn ^:async with-transaction!
  "Reserve one client; commit success, roll back rejection, and always release it."
  [pool operation]
  (let [client (await (.connect pool))
        discard? (atom false)]
    (try
      (await (.query client "BEGIN"))
      (try
        (let [value (await (operation client))]
          (await (.query client "COMMIT"))
          value)
        (catch :default error
          (try
            (await (.query client "ROLLBACK"))
            (catch :default rollback-error
              (reset! discard? true)
              (js/console.error "ROLLBACK failed; discarding client" rollback-error)))
          (throw error)))
      (finally (.release client @discard?)))))

(defn provider-binding-conflict?
  "Decode PostgreSQL's exact provider-binding unique-constraint failure."
  [error]
  (and (= "23505" (.-code error))
       (= "idx_provider_bindings_subject" (.-constraint error))))
