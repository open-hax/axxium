(ns axxium.law.event
  "Contract for clio-shaped events and for appending them to a ledger.
   Identity follows clio.law.event (open-hax/clio 9b14cda); the envelope keys
   follow Katamorph's message shape."
  (:require [axxium.law.did :as did]
            [malli.core :as m]))

(def uuid-pattern
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

(defn uuid-string? [value]
  (boolean (and (string? value) (re-matches uuid-pattern value))))

(def EventContract
  [:map {:closed true}
   [:event/id [:fn uuid-string?]]
   [:event/stream [:string {:min 1}]]
   [:event/seq pos-int?]
   [:event/causes [:vector [:fn uuid-string?]]]
   [:event/type :qualified-keyword]
   [:event/actor did/Did]
   [:event/at inst?]
   [:event/body [:map {:closed false}]]])

(defn valid-event?
  "Admit an event when its envelope matches and its causes are distinct and
   never include the event itself."
  [event]
  (and (m/validate EventContract event)
       (let [causes (:event/causes event)]
         (and (= (count causes) (count (set causes)))
              (not (some #{(:event/id event)} causes))))))

(defn explain-event [event]
  (m/explain EventContract event))

(defn next-seq
  "The seq the next event on `stream` must carry (law 1: contiguous from 1)."
  [ledger stream]
  (inc (count (filter #(= stream (:event/stream %)) ledger))))

(defn appendable?
  "Law 1 and 2: valid envelope, contiguous stream seq, fresh id, known causes."
  [ledger event]
  (let [ids (into #{} (map :event/id) ledger)]
    (and (valid-event? event)
         (= (:event/seq event) (next-seq ledger (:event/stream event)))
         (not (contains? ids (:event/id event)))
         (every? ids (:event/causes event)))))
