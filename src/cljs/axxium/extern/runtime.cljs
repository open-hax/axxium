(ns axxium.extern.runtime
  "Node environment, filesystem and time boundaries."
  (:require ["node:fs" :as fs]
            ["node:crypto" :as crypto]))

(defn env "Read one environment variable with a default." [key fallback]
  (or (aget (.-env js/process) key) fallback))
(defn json-file "Read operator-provisioned JSON; never fetch trust over the network." [path]
  (when (seq path) (js->clj (js/JSON.parse (.readFileSync fs path "utf8")) :keywordize-keys true)))
(defn sha256 "Hash a session token for persistence." [value]
  (-> (.createHash crypto "sha256") (.update value) (.digest "hex")))
(defn now-seconds "Current epoch seconds." [] (js/Math.floor (/ (.now js/Date) 1000)))
(defn expires-at "ISO timestamp after a bounded number of hours." [hours]
  (.toISOString (js/Date. (+ (.now js/Date) (* hours 3600000)))))
(defn fatal! "Terminate after a sanitized startup failure." [] (.exit js/process 1))
