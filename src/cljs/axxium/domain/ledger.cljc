(ns axxium.domain.ledger
  "A ledger is an immutable vector of clio events. Appending returns a new
   vector or throws; nothing is mutated in place."
  (:require [axxium.law.event :as law]))

(def empty-ledger [])

(defn event
  "Build an event for `stream` at the next seq. Id and instant are supplied by
   the caller so this stays pure."
  [ledger {:keys [id stream type actor at body causes]}]
  {:event/id id
   :event/stream stream
   :event/seq (law/next-seq ledger stream)
   :event/causes (vec causes)
   :event/type type
   :event/actor actor
   :event/at at
   :event/body body})

(defn append
  [ledger event]
  (when-not (law/appendable? ledger event)
    (throw (ex-info "Event is not appendable"
                    {:axxium/error :axxium.ledger/not-appendable
                     :event/id (:event/id event)
                     :event/stream (:event/stream event)
                     :explain (law/explain-event event)})))
  (conj ledger event))

(defn emit
  "Build and append in one step. Returns [ledger' event]."
  [ledger spec]
  (let [e (event ledger spec)]
    [(append ledger e) e]))

(defn stream [ledger stream-name]
  (filterv #(= stream-name (:event/stream %)) ledger))
