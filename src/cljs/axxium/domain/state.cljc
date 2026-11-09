(ns axxium.domain.state
  "The projection of an Axxium ledger (law 3): a left fold of every domain
   step over the events. Later PRs add steps; each is a pure (state, event)
   -> state function."
  (:require [axxium.domain.actor :as actor]
            [axxium.domain.authority :as authority]
            [axxium.domain.resource :as resource]))

(def empty-state {:actors {} :grants {} :resources {}})

(def ^:private base-steps [actor/step authority/step resource/step])

(defn step-with [steps]
  (fn [state event] (reduce (fn [s f] (f s event)) state steps)))

(defn fold
  ([ledger] (fold base-steps ledger))
  ([steps ledger] (reduce (step-with steps) empty-state ledger)))
