(ns axxium.extern.env
  "Node process environment boundary.")

(defn get-value [key]
  (aget (.-env js/process) key))

(defn parse-int [value]
  (js/parseInt value 10))
