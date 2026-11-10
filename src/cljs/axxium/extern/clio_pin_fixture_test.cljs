(ns axxium.extern.clio-pin-fixture-test
  (:require [clio.shape.canonical :as canonical]
            ["node:vm" :as vm]))

(defn bounded-canonical-edn
  "Exercise the real synchronous encoder with a deadline. Let expiry surface as a test error."
  [event]
  (vm/runInNewContext "encode()"
                     #js {:encode #(canonical/canonical-edn event)}
                     #js {:timeout 1000}))
