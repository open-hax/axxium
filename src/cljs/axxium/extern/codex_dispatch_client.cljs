(ns axxium.extern.codex-dispatch-client
  "Node-side bridge between durable intent custody and native observation."
  (:require ["node:crypto" :as crypto]
            [axxium.extern.codex-intent-store :as store]
            [axxium.extern.codex-peer-reader :as reader]
            [axxium.law.codex-dispatch :as dispatch]
            [axxium.law.codex-native-observation :as native]
            [promesa.core :as p]))

(def ^:private inbox-hint
  {"jsonrpc" "2.0"
   "method" "notifications/resources/updated"
   "params" {"uri" "axxium://mailbox/inbox"}})

(defn notice-sha256
  "Hash the exact UTF-8 text of one Codex notice."
  [text]
  (when-not (string? text)
    (throw (ex-info "A Codex notice must be text" {:reason :invalid-notice})))
  (-> (.createHash crypto "sha256")
      (.update text "utf8")
      (.digest "hex")))

(defn- reconcile-intent [intent approved-reader evidence]
  (let [{:keys [observation error]} (native/normalize intent approved-reader evidence notice-sha256)]
    (if error
      {:disposition :hold :reason error}
      (dispatch/reconcile intent observation))))

(defn reconcile-capture!
  "Read a saved intent and classify a pre-authenticated native capture.

   The outcome is advisory. This function issues no RPC and writes no
   receipt. The owner of the durable intent must apply and persist any
   disposition before advancing to another notification."
  [directory caller-id approved-reader evidence]
  (if-let [{:keys [intent state history-anchor]} (store/recover! directory caller-id)]
    (if (= :prepared state)
      {:disposition :hold :reason :not-attempted}
      (reconcile-intent (cond-> intent history-anchor (assoc :history-anchor history-anchor))
                        approved-reader evidence))
    {:disposition :hold :reason :missing-intent}))

(defn retire-capture!
  "Apply a completed native capture to this intent's durable checkpoint.

   The caller independently authorizes the peer and any successor notice.
   Native queue and mailbox effects stay outside this operation."
  [directory caller-id approved-reader evidence successor-digest]
  (store/checkpoint-terminal! directory caller-id successor-digest
                              #(reconcile-intent % approved-reader evidence)))

(defn- reconcile-saved! [directory caller-id addon config]
  (p/let [saved (store/recover! directory caller-id)]
    (cond
      (nil? saved) {:disposition :hold :reason :missing-intent}
      (not= (:binding config) (get-in saved [:intent :binding]))
      {:disposition :hold :reason :binding-mismatch}
      (= :prepared (:state saved)) {:disposition :hold :reason :not-attempted}
      (= :retired (:state saved)) (store/confirm-terminal! directory caller-id (:binding config))
      :else
      (p/let [{:keys [approved-reader evidence]} (if-let [anchor (:history-anchor saved)]
                                                  (reader/capture! addon config anchor)
                                                  (reader/capture! addon config))]
        (retire-capture! directory caller-id approved-reader evidence nil)))))

(defn inbox-intake
  "Return one worker for the exact saved intent and independently approved peer.

   Call it with the binding verified by the existing Axxium subscription
   adapter and its decoded string-keyed hint. Calls during a capture share the
   result and coalesce into one pending read. A terminal checkpoint satisfies
   this worker's original intent."
  [directory caller-id addon config]
  (let [state (atom {})]
    (letfn [(drain! []
              (swap! state assoc :pending? false)
              (p/handle (p/then (p/resolved nil)
                                (fn [_] (reconcile-saved! directory caller-id addon config)))
                        (fn [result error]
                          (if (and (:pending? @state) (not= :retired (:state result)))
                            (drain!)
                            (do
                              (reset! state {})
                              (if error (throw error) result))))))]
      (fn [verified-binding frame]
        (if (and (some? verified-binding)
                 (= (:binding config) verified-binding)
                 (= inbox-hint frame))
          (do
            (swap! state assoc :pending? true)
            (or (:running @state)
                (let [running (p/then (p/resolved nil) (fn [_] (drain!)))]
                  (swap! state assoc :running running)
                  running)))
          (p/resolved {:disposition :ignored}))))))
