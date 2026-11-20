(ns axxium.law.codex-native-observation
  "Normalize bound Codex app-server v2 reads for one saved dispatch intent."
  (:require [clojure.string :as str]))

(def ^:private queue-method "thread/queue/list")
(def ^:private turns-method "thread/turns/list")
(def ^:private max-pages-per-method 16)
(def ^:private max-entries-per-page 128)
(def ^:private max-history-items 4096)

(def ^:private turn-status
  {"completed" :completed, "failed" :failed, "interrupted" :interrupted,
   "inProgress" :in-progress})

(def ^:private item-types
  #{"userMessage" "hookPrompt" "agentMessage" "functionCallOutput" "plan"
    "reasoning" "commandExecution" "fileChange" "mcpToolCall" "dynamicToolCall"
    "collabAgentToolCall" "subAgentActivity" "webSearch" "imageView" "sleep"
    "imageGeneration" "enteredReviewMode" "exitedReviewMode" "contextCompaction"})

(defn- identity-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- rpc-id? [value]
  (or (identity-string? value)
      (and (integer? value) (<= -9007199254740991 value 9007199254740991))))

(defn- binding? [binding]
  (and (map? binding)
       (= #{:actor-did :device-id :thread-id :generation} (set (keys binding)))
       (every? identity-string? (vals binding))))

(defn- present-or-nil? [value]
  (or (nil? value) (identity-string? value)))

(defn- unique-by? [key-fn records]
  (= (count records) (count (into #{} (map key-fn) records))))

(defn- validated-page
  [method thread-id method-params? {:keys [request response]}]
  (let [request-id (get request "id")
        params (get request "params")
        page-limit (get params "limit")
        result (get response "result")
        records (get result "data")
        next-cursor (get result "nextCursor")]
    (when (and (map? request) (map? response) (map? params) (map? result)
               (= "2.0" (get request "jsonrpc" "2.0") (get response "jsonrpc" "2.0"))
               (rpc-id? request-id)
               (= request-id (get response "id"))
               (= method (get request "method"))
               (not (contains? response "error"))
               (not (contains? response "method"))
               (= thread-id (get params "threadId"))
               (int? page-limit) (<= 1 page-limit max-entries-per-page)
               (method-params? params)
               (vector? records) (<= (count records) page-limit)
               (contains? result "nextCursor")
               (present-or-nil? next-cursor)
               (or (nil? next-cursor) (seq records)))
      {:request-id request-id :params params :records records :next-cursor next-cursor})))

(defn- collect-pages [method thread-id method-params? pages]
  (when (and (vector? pages) (<= 1 (count pages) max-pages-per-method)
             (every? map? pages))
    (loop [remaining (seq pages)
           expected-cursor nil
           parameters nil
           known-cursors #{}
           ids []
           rows []]
      (when-let [{:keys [request-id params records next-cursor]}
                 (validated-page method thread-id method-params? (first remaining))]
        (let [cursor (get params "cursor")
              page-params (dissoc params "cursor")
              following (next remaining)
              accumulated (into rows records)
              request-ids (conj ids request-id)]
          (when (and (= expected-cursor cursor)
                     (or (nil? parameters) (= parameters page-params)))
            (cond
              (and (nil? next-cursor) (nil? following))
              {:records accumulated :request-ids request-ids}

              (and next-cursor following (not (contains? known-cursors next-cursor)))
              (recur following next-cursor page-params (conj known-cursors next-cursor)
                     request-ids accumulated))))))))

(def ^:private basic-params #{"threadId" "limit" "cursor"})

(defn- queue-params? [params]
  (every? basic-params (keys params)))

(defn- direction? [direction]
  (contains? #{"asc" "desc"} direction))

(defn- turns-params? [params]
  (and (every? (conj basic-params "itemsView" "sortDirection") (keys params))
       (= "full" (get params "itemsView"))
       (direction? (get params "sortDirection" "desc"))))

(defn- native-turn? [turn]
  (and (map? turn)
       (identity-string? (get turn "id"))
       (contains? turn-status (get turn "status"))
       (vector? (get turn "items"))
       (= "full" (get turn "itemsView" "full"))))

(defn- native-item? [item]
  (and (map? item)
       (identity-string? (get item "id"))
       (contains? item-types (get item "type"))
       (or (not= "userMessage" (get item "type"))
           (and (vector? (get item "content"))
                (present-or-nil? (get item "clientId"))))))

(defn- native-queue-row? [row]
  (and (map? row)
       (identity-string? (get row "id"))
       (identity-string? (get row "clientUserMessageId"))
       (vector? (get row "input"))))

(defn- normalized-digest [digest-text input]
  (when (= 1 (count input))
    (let [text-part (first input)]
      (when (and (map? text-part)
                 (every? #{"type" "text" "text_elements"} (keys text-part))
                 (= "text" (get text-part "type"))
                 (string? (get text-part "text"))
                 (= [] (get text-part "text_elements" [])))
        (try
          (let [digest (digest-text (get text-part "text"))]
            (when (and (string? digest) (re-matches #"[0-9a-f]{64}" digest))
              digest))
          (catch #?(:clj Exception :cljs :default) _ nil))))))

(defn- normalized-queue [intent rows digest-text]
  (let [{:keys [caller-id queue-id]} intent]
    (when (and (every? native-queue-row? rows)
               (unique-by? #(get % "id") rows)
               (not-any? #(and (= queue-id (get % "id"))
                               (not= caller-id (get % "clientUserMessageId"))) rows))
      (let [matched (filter #(= caller-id (get % "clientUserMessageId")) rows)
            normalized (mapv (fn [row]
                               (when-let [digest (normalized-digest digest-text (get row "input"))]
                                 {:queue-id (get row "id") :caller-id caller-id
                                  :notice-sha256 digest}))
                             matched)]
        (when (every? some? normalized) normalized)))))

(defn- normalize-turn [caller-id turn digest-text]
  (let [items (get turn "items")]
    (when (and (every? native-item? items) (unique-by? #(get % "id") items))
      (let [matched (filter #(and (= "userMessage" (get % "type"))
                                  (= caller-id (get % "clientId"))) items)
            history (mapv (fn [item]
                            (when-let [digest (normalized-digest digest-text (get item "content"))]
                              {:turn-id (get turn "id") :item-id (get item "id")
                               :status (get turn-status (get turn "status"))
                               :caller-id caller-id :notice-sha256 digest}))
                          matched)]
        (when (every? some? history) history)))))

(defn- normalized-history [caller-id turns digest-text]
  (when (and (every? native-turn? turns)
             (<= (transduce (map #(count (get % "items"))) + turns) max-history-items)
             (unique-by? #(get % "id") turns))
    (let [history (mapv #(normalize-turn caller-id % digest-text) turns)]
      (when (every? some? history) (into [] cat history)))))

(defn- normalize-reads [intent evidence digest-text]
  (let [thread-id (get-in intent [:binding :thread-id])
        raw-queue-pages (:queue-pages evidence)
        ;; Native queue cursors are offsets into the current queue. Only one
        ;; initial response with no continuation proves complete coverage.
        queue-pages (when (and (vector? raw-queue-pages) (= 1 (count raw-queue-pages)))
                      (collect-pages queue-method thread-id queue-params? raw-queue-pages))
        turn-pages (collect-pages turns-method thread-id turns-params? (:turn-pages evidence))]
    (cond
      (nil? queue-pages) {:error :incomplete-queue}
      (nil? turn-pages) {:error :incomplete-history}
      :else
      (let [ids (into (:request-ids queue-pages) (:request-ids turn-pages))
            queue (normalized-queue intent (:records queue-pages) digest-text)
            history (normalized-history (:caller-id intent) (:records turn-pages) digest-text)]
        (if (and queue history (unique-by? identity ids))
          {:observation {:binding (:binding intent) :queue-complete? true :history-complete? true
                         :queue queue :history history}}
          {:error :invalid-native-evidence})))))

(defn normalize
  "Validate complete native request/response chains against an approved reader and saved intent.

   The transport authenticates the reader independently before and after
   collection, verifies every reply came from it, and applies byte and time
   budgets. Evidence carries those two reader attestations and paired queue
   and full-turn pages. Queue coverage requires one initial terminal page.
   Full-turn history allows 16 pages and 4096 items total. Each page accepts
   up to 128 entries and retains its native
   request and response with string keys. The pure digest-text function
   hashes the exact UTF-8 notice text.
   Returns a projection for codex-dispatch/reconcile or a reason to hold."
  [intent approved-reader evidence digest-text]
  (cond
    (not (and (map? intent)
              (binding? (:binding intent))
              (identity-string? (:caller-id intent))
              (present-or-nil? (:queue-id intent))
              (fn? digest-text)))
    {:error :invalid-intent}

    (not (and (map? approved-reader)
              (map? evidence)
              (identity-string? (:identity approved-reader))
              (= (:binding intent) (:binding approved-reader))
              (= approved-reader (:reader-before evidence) (:reader-after evidence))))
    {:error :reader-binding-mismatch}

    (not (nil? (:item-pages evidence)))
    {:error :unsupported-native-history}

    :else
    (normalize-reads intent evidence digest-text)))
