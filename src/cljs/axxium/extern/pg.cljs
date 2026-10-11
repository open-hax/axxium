(ns axxium.extern.pg
  "Thin extern wrapper around the `pg` npm Pool.
   Following knoxx.backend.extern.pg patterns."
  (:require ["pg" :as pg-lib]))

(defn create-pool!
  [{:keys [connection-string host port database user password max idle-timeout-ms connect-timeout-ms]}]
  (new (.-Pool pg-lib)
       (clj->js {:connectionString connection-string
                   :host host :port port :database database :user user :password password
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
  (let [params-arr (when (seq params) (into-array (map #(if (or (map? %) (vector? %) (set? %))
                                        (js/JSON.stringify (clj->js %)) %) params)))]
    (-> (.query conn sql-str params-arr)
        (.then keywordize-rows))))

(defn query-one!
  "Execute SQL and return Promise<first-row-as-CLJS-map | nil>."
  [conn sql-str params]
  (-> (query! conn sql-str params)
      (.then (fn [{:keys [rows]}] (first rows)))))

(defn ^:async transaction! "Run a callback on one connection with rollback on any failure." [pool f]
  (let [client (await (.connect pool))]
    (try
      (await (query! client "BEGIN" []))
      (let [value (await (f client))]
        (await (query! client "COMMIT" [])) value)
      (catch :default error
        (await (query! client "ROLLBACK" []))
        (throw error))
      (finally (.release client)))))
