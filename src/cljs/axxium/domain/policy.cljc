(ns axxium.domain.policy
  "Decide, record, then act (law 7). A request is
   {:actor did :resource id :action kw :at inst}. The decision is appended to
   the `policy/decisions` stream before any effect; the effect names the
   decision as a cause."
  (:require [axxium.domain.authority :as authority]
            [axxium.domain.ledger :as ledger]))

(def decisions-stream "policy/decisions")

(defn decide
  "Pure decision. `extra-allow?` lets a caller add a rule (e.g. an owner may
   always act on their own resource) without bypassing the record."
  ([state request] (decide state request (constantly false)))
  ([state {:keys [actor resource action at] :as request} extra-allow?]
   (let [granted? (authority/allowed? state actor resource action at)
         extra? (and (not granted?) (boolean (extra-allow? state request)))]
     {:decision/request (select-keys request [:actor :resource :action])
      :decision/allow? (or granted? extra?)
      :decision/reason (cond granted? :grant extra? :rule :else :no-grant)})))

(defn act
  "Append the decision, then the effect only when allowed.
   `ids` = {:decision-id :effect-id :at}. `effect` is an event spec without
   :id, :at or :causes, or nil when the decision itself is the record (e.g. a read). Returns {:ledger :decision :effect}."
  ([lg state request effect ids] (act lg state request effect ids (constantly false)))
  ([lg state request effect {:keys [decision-id effect-id at]} extra-allow?]
   (let [decision (decide state request extra-allow?)
         [lg' d-event] (ledger/emit lg {:id decision-id
                                        :stream decisions-stream
                                        :type :axxium.policy/decided
                                        :actor (:actor request)
                                        :at at
                                        :body decision})]
     (if (and (:decision/allow? decision) effect)
       (let [[lg'' e-event] (ledger/emit lg' (assoc effect
                                                    :id effect-id
                                                    :at at
                                                    :causes [(:event/id d-event)]))]
         {:ledger lg'' :decision d-event :effect e-event})
       {:ledger lg' :decision d-event :effect nil}))))

(defn decision-before-effect?
  "Law 7 checked over a whole ledger: every event caused by a decision has an
   allowing decision earlier in the ledger."
  [lg]
  (let [by-id (into {} (map (juxt :event/id identity)) lg)
        position (into {} (map-indexed (fn [i e] [(:event/id e) i])) lg)]
    (every? (fn [e]
              (let [ds (->> (:event/causes e)
                            (map by-id)
                            (filter #(= :axxium.policy/decided (:event/type %))))]
                (every? #(and (get-in % [:event/body :decision/allow?])
                              (< (position (:event/id %)) (position (:event/id e))))
                        ds)))
            lg)))
