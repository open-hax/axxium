(ns axxium.extern.codex-dispatch-client
  "Node-side bridge between durable intent custody and native observation."
  (:require ["node:crypto" :as crypto]
            [axxium.extern.codex-intent-store :as store]
            [axxium.law.codex-dispatch :as dispatch]
            [axxium.law.codex-native-observation :as native]))

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
  (if-let [{:keys [intent state]} (store/recover! directory caller-id)]
    (if (= :prepared state)
      {:disposition :hold :reason :not-attempted}
      (reconcile-intent intent approved-reader evidence))
    {:disposition :hold :reason :missing-intent}))

(defn retire-capture!
  "Apply a completed native capture to this intent's durable checkpoint.

   The caller independently authorizes the peer and any successor notice.
   Native queue and mailbox effects stay outside this operation."
  [directory caller-id approved-reader evidence successor-digest]
  (store/checkpoint-terminal! directory caller-id successor-digest
                              #(reconcile-intent % approved-reader evidence)))
