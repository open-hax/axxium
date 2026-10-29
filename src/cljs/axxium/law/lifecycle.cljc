(ns axxium.law.lifecycle
  "Admission contract for installing process-wide shutdown handling."
  (:require [malli.core :as m]))

(def Installation
  "Opaque pool handles are represented only by their close capability."
  [:map {:closed true}
   [:runtime-pool-close? [:= true]]
   [:schema-pool-close? [:= true]]
   [:statement-timeout-ms [:int {:min 1000 :max 300000}]]])

(defn require-installation!
  "Reject invalid capabilities or timeout before any signal handler is added."
  [installation]
  (when-not (m/validate Installation installation)
    (throw (ex-info "Invalid lifecycle installation" {})))
  installation)
