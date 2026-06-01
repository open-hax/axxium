(ns axxium.extern.pg
  "Thin extern wrapper around the `pg` npm Pool.
   Following knoxx.backend.extern.pg patterns."
  (:require ["pg" :as pg-lib]))

(defn create-pool!
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
