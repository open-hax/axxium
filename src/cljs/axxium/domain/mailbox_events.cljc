(ns axxium.domain.mailbox-events
  "Durable state for the opt-in mailbox webhook pilot. The transport verifies
   the callback and current credential before invoking these transitions."
  (:require [axxium.domain.authority :as authority]
            [axxium.domain.ledger :as ledger]
            [axxium.domain.mailbox :as mailbox]
            [axxium.domain.state :as state]
            [axxium.law.did :as did]
            [axxium.law.event :as law]))

(def ^:private subscriptions "mailbox-subscriptions")
(def ^:private outbox "outbox")
(def ^:private max-subscriptions 16)
(def ^:private max-ttl-ms 86400000)
(def ^:private max-attempts 3)
(def ^:private attempt-lease-ms 30000)

(defn- subscription-id? [value]
  (and (string? value) (re-matches #"sub_[0-9a-f]{64}" value)))

(defn- sealed-callback? [value]
  (and (string? value)
       (<= (count value) 8192)
       (re-matches #"v1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+" value)))

(defn- live? [record at]
  (and record (< (inst-ms at) (inst-ms (:expires record)))))

(defn- can-read? [auth owner at]
  (authority/allowed? auth owner (mailbox/stream owner) :read at))

(defn authorized?
  "Check the current owner read grant before issuing a callback challenge."
  [lg owner at]
  (and (did/did? owner) (inst? at) (can-read? (state/fold lg) owner at)))

(defn- next-id [lg]
  (let [digits (str (inc (count lg)))]
    (str "b0000000-0000-4000-8000-" (apply str (repeat (- 12 (count digits)) "0")) digits)))

(defn- refuse [lg error]
  {:ledger lg :error error})

(defn- record [lg spec result]
  {:ledger (first (ledger/emit lg spec)) :result result})

(defn- project [lg]
  (reduce
   (fn [view {:event/keys [actor body type stream] :as event}]
     (cond
       (= subscriptions stream)
       (case type
         :axxium.subscription/activated (assoc-in view [:subscriptions actor] body)
         :axxium.subscription/removed (update view :subscriptions dissoc actor)
         view)

       (= :axxium.mailbox/accepted type)
       (let [owner (:message/to body)]
         (if (and (= stream (mailbox/stream owner)) (= (:event/id event) (:message/id body)))
           (update-in view [:accepted owner] (fnil conj []) event)
           view))

       (= outbox stream)
       (let [delivery-id (:outbox/delivery-event-id body)
             message-id (:outbox/message-id body)]
         (case type
           (:axxium.outbox/webhook-attempted
            :axxium.outbox/webhook-retry
            :axxium.outbox/webhook-terminal)
           (assoc-in view [:delivery delivery-id] event)

           :axxium.outbox/delivered
           (update view :closed conj message-id)

           :axxium.outbox/dispatched
           (if (= :poll (:outbox/channel body))
             (update view :closed conj message-id)
             view)

           view))

       :else view))
   {:subscriptions {} :accepted {} :delivery {} :closed #{}}
   lg))

(defn subscription
  "Latest subscription for an owner, if present. Expiry is checked at use."
  [lg owner]
  (get-in (project lg) [:subscriptions owner]))

(defn subscribe
  "Record a verified callback for its owner. Refreshing its active identity
   replaces the sealed callback but keeps the original mailbox boundary."
  [lg {:keys [id owner generation sealed expires at]}]
  (if-not (and (subscription-id? id) (did/did? owner) (law/uuid-string? generation)
               (sealed-callback? sealed) (inst? expires) (inst? at)
               (<= 1 (- (inst-ms expires) (inst-ms at)) max-ttl-ms))
    (refuse lg :invalid-subscription)
    (let [view (project lg)
          current (get-in view [:subscriptions owner])
          active? (live? current at)
          after (if active? (:after current) (dec (law/next-seq lg (mailbox/stream owner))))
          result {:id id :owner owner :generation generation :sealed sealed
                  :expires expires :after after}]
      (cond
        (not (authorized? lg owner at))
        (refuse lg :forbidden)

        (and active? (= current result))
        {:ledger lg :result current}

        (and active? (not= id (:id current)))
        (refuse lg :subscription-conflict)

        (some #(= generation (:event/id %)) lg)
        (refuse lg :generation-conflict)

        (and (not active?)
             (>= (count (filter #(live? % at) (vals (:subscriptions view))))
                 max-subscriptions))
        (refuse lg :subscription-capacity)

        :else
        (record lg {:id generation :stream subscriptions :type :axxium.subscription/activated
                    :actor owner :at at :body result
                    :causes (when current [(:generation current)])}
                result)))))

(defn unsubscribe
  "Remove only the caller's matching subscription. Repetition is harmless."
  [lg owner id at]
  (if-not (and (did/did? owner) (subscription-id? id) (inst? at))
    (refuse lg :invalid-subscription)
    (if-let [current (subscription lg owner)]
      (if (= id (:id current))
        (record lg {:id (next-id lg) :stream subscriptions :type :axxium.subscription/removed
                    :actor owner :at at :body {:id id} :causes [(:generation current)]}
                {})
        {:ledger lg :result {}})
      {:ledger lg :result {}})))

(defn- candidate [sub event attempt-number]
  (let [{:keys [id owner generation]} sub
        message-id (:event/id event)]
    {:subscription-id id :generation generation :owner owner
     :message-id message-id :message-at (:event/at event)
     :event-id (str "evt_" (subs id 4) "_" message-id)
     :attempt attempt-number}))

(defn- retry-delay-ms [attempt-number]
  (* 5000 attempt-number))

(defn- eligible-message [view sub event at]
  (let [base (candidate sub event 1)
        delivery (get-in view [:delivery (:event-id base)])
        prior-attempt (get-in delivery [:event/body :outbox/attempt] 0)
        phase (:event/type delivery)
        elapsed (when delivery (- (inst-ms at) (inst-ms (:event/at delivery))))]
    (when (and (< (:after sub) (:event/seq event))
               (not (contains? (:closed view) (:message-id base)))
               (not= :axxium.outbox/webhook-terminal phase)
               (< prior-attempt max-attempts))
      (case phase
        nil base
        :axxium.outbox/webhook-attempted
        (when (>= elapsed attempt-lease-ms)
          (assoc base :attempt (inc prior-attempt)))
        :axxium.outbox/webhook-retry
        (when (>= elapsed (retry-delay-ms prior-attempt))
          (assoc base :attempt (inc prior-attempt)))))))

(defn- due-for [view auth sub at]
  (when (and (live? sub at) (can-read? auth (:owner sub) at))
    (some #(eligible-message view sub % at) (get-in view [:accepted (:owner sub)]))))

(defn due-deliveries
  "Select at most one due message per active owner. Accepted mailbox events
   remain authoritative even when an older append lost its pending outbox."
  [lg at]
  (if (inst? at)
    (let [view (project lg)
          auth (state/fold lg)]
      (into [] (comp (keep #(due-for view auth % at)) (take max-subscriptions))
            (sort-by :owner (vals (:subscriptions view)))))
    []))

(defn- delivery-body [{:keys [subscription-id generation message-id event-id attempt]}]
  {:outbox/subscription-id subscription-id :outbox/generation generation
   :outbox/message-id message-id :outbox/delivery-event-id event-id :outbox/attempt attempt})

(defn attempt
  "Persist the attempt before I/O. An unsettled attempt becomes retryable
   after its lease, using the same delivery event identity."
  [lg proposed at]
  (let [view (project lg)
        sub (get-in view [:subscriptions (:owner proposed)])
        current (when (inst? at) (due-for view (state/fold lg) sub at))]
    (if (and current (= current proposed))
      (record lg {:id (next-id lg) :stream outbox :type :axxium.outbox/webhook-attempted
                  :actor (:owner current) :at at :body (delivery-body current)
                  :causes [(:message-id current) (:generation current)]}
              current)
      (refuse lg :delivery-not-due))))

(defn- delivery-outcome [status attempt-number]
  (cond
    (and status (<= 200 status 299)) :delivered
    (and (< attempt-number max-attempts)
         (or (nil? status) (contains? #{408 429} status) (<= 500 status 599))) :retry
    :else :terminal))

(defn settle
  "Settle only the currently persisted attempt for the active generation.
   A 2xx records webhook receipt independently of mailbox acknowledgment."
  [lg proposed status at]
  (if-not (and (inst? at) (or (nil? status) (and (int? status) (<= 100 status 599))))
    (refuse lg :invalid-delivery-outcome)
    (let [view (project lg)
          owner (:owner proposed)
          sub (get-in view [:subscriptions owner])
          delivery (get-in view [:delivery (:event-id proposed)])
          accepted (some #(when (= (:message-id proposed) (:event/id %)) %)
                         (get-in view [:accepted owner]))
          count-attempts (get-in delivery [:event/body :outbox/attempt])]
      (if-not (and (live? sub at)
                   (can-read? (state/fold lg) owner at)
                   accepted
                   (not (contains? (:closed view) (:message-id proposed)))
                   (= :axxium.outbox/webhook-attempted (:event/type delivery))
                   (>= (inst-ms at) (inst-ms (:event/at delivery)))
                   (= (:event/body delivery) (delivery-body proposed))
                   (= proposed (candidate sub accepted count-attempts)))
        (refuse lg :delivery-not-current)
        (let [outcome (delivery-outcome status count-attempts)
              type (case outcome
                     :delivered :axxium.outbox/delivered
                     :retry :axxium.outbox/webhook-retry
                     :terminal :axxium.outbox/webhook-terminal)
              body (cond-> (delivery-body proposed)
                     status (assoc :outbox/http-status status)
                     (= :delivered outcome)
                     (assoc :outbox/channel :push
                            :outbox/transport-receipt {:status status}))]
          (record lg {:id (next-id lg) :stream outbox :type type
                      :actor owner :at at :body body :causes [(:event/id delivery)]}
                  {:status outcome}))))))
