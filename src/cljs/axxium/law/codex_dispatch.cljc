(ns axxium.law.codex-dispatch
  "Reconciliation of an already durable Codex Desktop notice intent."
  (:require [clojure.string :as str]))

(defn- id? [value]
  (and (string? value) (not (str/blank? value))))

(defn- digest? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn- binding? [binding]
  (and (map? binding)
       (= #{:actor-did :device-id :thread-id :generation} (set (keys binding)))
       (every? id? (vals binding))))

(defn- notice? [{:keys [caller-id notice-sha256]}]
  (and (id? caller-id) (digest? notice-sha256)))

(defn- intent? [intent]
  (and (map? intent)
       (notice? intent)
       (binding? (:binding intent))
       (let [queue-id (:queue-id intent)]
         (or (nil? queue-id) (id? queue-id)))))

(defn- queue-row? [row]
  (and (map? row) (notice? row) (id? (:queue-id row))))

(defn- turn-item? [item]
  (and (map? item)
       (notice? item)
       (id? (:turn-id item))
       (id? (:item-id item))
       (contains? #{:completed :failed :interrupted :in-progress} (:status item))))

(defn- valid-projection? [rows row? identity-key]
  (and (vector? rows)
       (every? row? rows)
       (= (count rows) (count (into #{} (map identity-key) rows)))))

(defn- hold [reason]
  {:disposition :hold :reason reason})

(defn- classify
  [{:keys [caller-id notice-sha256 queue-id]} {:keys [queue history]}]
  (let [related? #(= caller-id (:caller-id %))
        queued (filterv related? queue)
        turns (filterv related? history)
        row (first queued)
        item (first turns)]
    (cond
      (or (< 1 (count queued)) (< 1 (count turns)))
      (hold :duplicate-caller)

      (and row item)
      (hold :queue-and-history)

      (and queue-id (some #(and (= queue-id (:queue-id %)) (not (related? %))) queue))
      (hold :queue-id-mismatch)

      (and row queue-id (not= queue-id (:queue-id row)))
      (hold :queue-id-mismatch)

      (and (or row item) (not= notice-sha256 (:notice-sha256 (or row item))))
      (hold :notice-mismatch)

      row
      {:disposition :accepted :queue-id (:queue-id row)}

      (= :completed (:status item))
      {:disposition :retire :turn-id (:turn-id item) :item-id (:item-id item)}

      item
      (hold :turn-not-completed)

      :else
      (hold :no-positive-evidence))))

(defn reconcile
  "Classify exact native queue and history observations for one saved intent.

   The caller verifies the source and completeness of both projections and
   supplies its currently authorized binding. A hold preserves all durable
   intent fields. Retirement is local and says only that the native notice's
   turn finished. This contract cannot authorize an add or acknowledge mail."
  [intent observation]
  (cond
    (not (intent? intent))
    (hold :invalid-intent)

    (not (map? observation))
    (hold :invalid-observation)

    (not= (:binding intent) (:binding observation))
    (hold :binding-mismatch)

    (not (and (true? (:queue-complete? observation)) (true? (:history-complete? observation))))
    (hold :incomplete-observation)

    (not (and (valid-projection? (:queue observation) queue-row? :queue-id)
              (valid-projection? (:history observation) turn-item? (juxt :turn-id :item-id))))
    (hold :invalid-observation)

    :else
    (classify intent observation)))
