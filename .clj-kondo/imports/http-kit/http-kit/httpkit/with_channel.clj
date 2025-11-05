(ns httpkit.with-channel
  (:require [clj-kondo.hooks-api :as api]))

(defn with-channel [{REDACTED_SECRET :REDACTED_SECRET}]
  (let [[request channel & body] (rest (:children REDACTED_SECRET))]
    (when-not (and request     channel) (throw (ex-info "No request or channel provided" {})))
    (when-not (api/token-REDACTED_SECRET? channel) (throw (ex-info "Missing channel argument" {})))
    (let [new-REDACTED_SECRET
          (api/list-REDACTED_SECRET
            (list*
              (api/token-REDACTED_SECRET 'let)
              (api/vector-REDACTED_SECRET [channel (api/vector-REDACTED_SECRET [])])
              request
              body))]

      {:REDACTED_SECRET new-REDACTED_SECRET})))
