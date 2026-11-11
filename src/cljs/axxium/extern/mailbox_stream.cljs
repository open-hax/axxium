(ns axxium.extern.mailbox-stream
  "Own the sockets and opt-ins for authenticated MCP inbox notifications."
  (:require [axxium.extern.http :as http]
            [axxium.extern.mailbox-mcp :as rpc]))

(def ^:private connection-limit 128)
(def ^:private subscription-limit 256)
(def ^:private subscription-ttl-ms 3600000)
(def ^:private heartbeat-ms 15000)

(def inbox-uri rpc/inbox-uri)

(def ^:private inbox-update
  (str "event: message\ndata: "
       (js/JSON.stringify
        #js {:jsonrpc "2.0" :method "notifications/resources/updated"
             :params #js {:uri inbox-uri}})
       "\n\n"))

(defn- live-subscription? [subscription actor-did now]
  (and (= actor-did (:actor-did subscription)) (< now (:expires-at subscription))))

(defn- prune! [runtime now]
  (swap! runtime update :subscriptions
         (fn [entries]
           (into {} (filter (fn [[_ {:keys [expires-at]}]] (< now expires-at))) entries))))

(defn subscribe!
  "Record the verified session's opt-in, even if its stream is not open yet."
  [runtime session-id actor-did]
  (let [now (js/Date.now)
        {:keys [closed? streams subscriptions]} (prune! runtime now)
        existing (get subscriptions session-id)
        stream (get streams session-id)]
    (cond
      closed? false
      (and stream (not= actor-did (:actor-did stream))) false
      existing (= actor-did (:actor-did existing))
      (>= (count subscriptions) subscription-limit) false
      :else (do (swap! runtime assoc-in [:subscriptions session-id]
                       {:actor-did actor-did :expires-at (+ now subscription-ttl-ms)})
                true))))

(defn unsubscribe!
  "Remove a session's inbox opt-in while leaving any connected stream open."
  [runtime session-id actor-did]
  (let [existing (get-in @runtime [:subscriptions session-id])]
    (when (or (nil? existing) (= actor-did (:actor-did existing)))
      (swap! runtime update :subscriptions dissoc session-id)
      true)))

(defn subscriptions
  "Return live opted-in streams for this recipient. The caller must authorize
   each handle and then write synchronously while holding its authority lock."
  [runtime actor-did]
  (let [now (js/Date.now)
        {:keys [streams subscriptions]} @runtime]
    (into []
          (keep (fn [[session-id stream]]
                  (when (live-subscription? (get subscriptions session-id) actor-did now)
                    stream)))
          streams)))

(defn dispatch!
  "Queue an inbox update after commit. Coalesce concurrent updates per
   recipient without blocking the sender's receipt."
  [runtime actor-did notify!]
  (let [{:keys [closed? pending]} @runtime]
    (when (and (not closed?) (seq (subscriptions runtime actor-did)))
      (cond
        (contains? pending actor-did)
        (swap! runtime assoc-in [:pending actor-did] true)

        (< (count pending) connection-limit)
        (letfn [(^:async deliver []
                  (try
                    (when-not (:closed? @runtime)
                      (await (notify!)))
                    (catch :default _ nil)
                    (finally
                      (if (true? (get-in @runtime [:pending actor-did]))
                        (do (swap! runtime assoc-in [:pending actor-did] false)
                            (js/queueMicrotask deliver))
                        (swap! runtime update :pending dissoc actor-did)))))]
          (swap! runtime assoc-in [:pending actor-did] false)
          (js/queueMicrotask deliver))))))

(defn- forget-stream! [runtime session-id stream-id]
  (when (= stream-id (get-in @runtime [:streams session-id :stream-id]))
    (swap! runtime (fn [state]
                     (-> state
                         (update :streams dissoc session-id)
                         (update :subscriptions dissoc session-id))))
    true))

(defn- open-stream! [runtime reply session-id actor-did authorize!]
  (let [response (.-raw reply)
        stream-id (js/Symbol)
        checking? (atom false)
        forget! #(forget-stream! runtime session-id stream-id)
        close! (fn []
                 (when (and (forget!) (not (.-destroyed response)))
                   (.destroy response)))
        write-frame! (fn [frame]
                       (when (= stream-id (get-in @runtime [:streams session-id :stream-id]))
                         (try
                           (if (.write response frame)
                             true
                             (do (close!) false))
                           (catch :default _
                             (close!)
                             false))))
        heartbeat! (fn ^:async heartbeat []
                     (if (compare-and-set! checking? false true)
                       (try
                         (if (await (authorize!))
                           (write-frame! ": heartbeat\n\n")
                           (close!))
                         (catch :default _
                           (close!))
                         (finally
                           (reset! checking? false)))
                       (close!)))
        write! (fn []
                 (when (live-subscription? (get-in @runtime [:subscriptions session-id])
                                           actor-did (js/Date.now))
                   (write-frame! inbox-update)))]
    (swap! runtime assoc-in [:streams session-id]
           {:stream-id stream-id :actor-did actor-did :session-id session-id
            :authorize! authorize! :write! write! :close! close! :heartbeat! heartbeat!})
    (.once response "close" forget!)
    (.once response "error" close!)
    (try
      (.hijack reply)
      (.writeHead response 200
                  #js {"Content-Type" "text/event-stream; charset=utf-8"
                       "Cache-Control" "no-store, no-transform"
                       "X-Accel-Buffering" "no"
                       "X-Content-Type-Options" "nosniff"})
      (write-frame! ": connected\n\n")
      (catch :default _
        (close!)))))

(defn listen!
  "Open one stream for an already authenticated mailbox MCP session."
  [runtime _req reply session-id actor-did authorize!]
  (let [{:keys [closed? streams subscriptions]} (prune! runtime (js/Date.now))
        existing (get subscriptions session-id)]
    (cond
      (or closed? (>= (count streams) connection-limit))
      (http/send-secret! reply 503 {:error "Mailbox notification streams are unavailable"})

      (or (contains? streams session-id)
          (some #(= actor-did (:actor-did %)) (vals streams))
          (and existing (not= actor-did (:actor-did existing))))
      (http/send-secret! reply 409 {:error "Mailbox already has a notification stream"})

      :else
      (open-stream! runtime reply session-id actor-did authorize!))))

(defn create!
  "Create a bounded listener and close every socket before Fastify drains."
  [app]
  (let [runtime (atom {:streams {} :subscriptions {} :pending {} :closed? false})
        timer (js/setInterval
               (fn []
                 (let [{:keys [streams]} (prune! runtime (js/Date.now))]
                   (doseq [{:keys [heartbeat!]} (vals streams)]
                     (heartbeat!))))
               heartbeat-ms)]
    (.unref timer)
    (.addHook app "preClose"
              (fn [done]
                (js/clearInterval timer)
                (let [{:keys [streams]} (swap! runtime assoc :closed? true :subscriptions {} :pending {})]
                  (doseq [{:keys [close!]} (vals streams)]
                    (close!)))
                (done)))
    runtime))
