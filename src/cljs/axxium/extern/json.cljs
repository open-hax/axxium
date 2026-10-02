(ns axxium.extern.json
  "JSON serialization for PostgreSQL JSONB parameters.")

(defn encode [value]
  (js/JSON.stringify
   (clj->js value :keyword-fn (fn [k] (subs (str k) 1)))))
