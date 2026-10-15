(ns axxium.shape.agent
  "Pure normalization of agent-creation request bodies. No admission decisions."
  (:require [clojure.string :as str]))

(defn creation-request
  "Normalize a decoded body into the agent-creation shape (trimmed display-name)."
  [body]
  (let [display-name (:display-name body)]
    {:display-name (when (string? display-name) (str/trim display-name))}))
