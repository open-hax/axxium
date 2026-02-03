(ns test-ecosystem
  (:require [clobber.macro]))

(clobber.macro/defapp "test-app" {:script "REDACTED_SECRET" :args ["-e" "console.log('hello')"]})
(clobber.macro/ecosystem-output)
