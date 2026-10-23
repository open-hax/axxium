(ns axxium.law.device-polling
  "Pure admission for an authenticated device client's durable polling record.
   The storage adapter must read, decide and persist under one atomic lock."
  (:require [malli.core :as m]))

(def registry
  "Named contracts for persisted device polling evidence and explicit clock input."
  {::timestamp [:int {:min 0 :max 253402300799999}]
   ::client-id [:string {:min 1 :max 128}]
   ::record [:map {:closed true}
             [:client-id [:ref ::client-id]]
             [:expires-at-ms [:ref ::timestamp]]
             [:last-poll-ms [:maybe [:ref ::timestamp]]]
             [:interval-seconds [:int {:min 5 :max 86400}]]
             [:consumed? :boolean]
             [:poll-count {:optional true} [:int {:min 0 :max 120}]]]})

(defn- valid?
  "Validate named polling evidence without installing a mutable global registry."
  [schema value]
  (m/validate [:ref schema] value {:registry (merge (m/default-schemas) registry)}))

(defn admit-poll
  "Return a refusal or a proposed durable update before the protocol engine runs.
   The caller client ID is authenticated adapter evidence, never a device body's
   unverified identity. No browser Origin or CSRF cookie is required for polling.
   A continuation does not approve a user or issue a token: the protocol engine
   must still enforce its own pending/denied/approved grant lifecycle."
  [record authenticated-client-id now-ms]
  (cond
    (not (valid? ::timestamp now-ms))
    {:decision :refuse :error :invalid-request}

    (or (not (valid? ::record record))
        (not (valid? ::client-id authenticated-client-id))
        (not= authenticated-client-id (:client-id record))
        (:consumed? record)
        (>= (get record :poll-count 0) 120))
    {:decision :refuse :error :invalid-grant}

    (>= now-ms (:expires-at-ms record))
    {:decision :refuse :error :expired-token}

    (and (:last-poll-ms record)
         (< now-ms (+ (:last-poll-ms record) (* 1000 (:interval-seconds record)))))
    ;; Refuse rather than propose a state outside the persisted-record contract
    ;; when repeated backoff reaches the supported interval bound.
    (if (> (+ 5 (:interval-seconds record)) 86400)
      {:decision :refuse :error :invalid-grant}
      {:decision :refuse :error :slow-down
       :record (-> record
                   (assoc :last-poll-ms (max now-ms (:last-poll-ms record)))
                   (update :interval-seconds + 5)
                   (update :poll-count (fnil inc 0)))})

    :else
    {:decision :continue :record (-> record (assoc :last-poll-ms now-ms)
                                      (update :poll-count (fnil inc 0)))}))
