(ns axxium.extern.codex-dispatch-client-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [axxium.extern.codex-dispatch-client :as client]
            [axxium.extern.codex-intent-store :as store]
            [cljs.test :refer [deftest is testing]]))

(def ^:private notice "Synthetic receive hint → café")
(def ^:private binding-id {:actor-did "did:web:fixture.example:actor"
                           :device-id "fixture-device"
                           :thread-id "fixture-thread"
                           :generation "fixture-binding"})
(def ^:private caller-id "11111111-1111-4111-8111-111111111111")
(def ^:private approved-reader {:identity "synthetic-reader" :binding binding-id})
(def ^:private input [{"type" "text" "text" notice "text_elements" []}])

(defn- page [id method params data]
  {:request {"id" id "method" method
             "params" (merge {"threadId" (:thread-id binding-id) "limit" 4} params)}
   :response {"id" id "result" {"data" data "nextCursor" nil}}})

(defn- capture [queue turns]
  {:reader-before approved-reader
   :reader-after approved-reader
   :queue-pages [(page 1 "thread/queue/list" {} queue)]
   :turn-pages [(page 2 "thread/turns/list" {"itemsView" "full"} turns)]})

(defn- check-reconciliation!
  []
  (let [directory (.realpathSync fs (.mkdtempSync fs (.join path (.tmpdir os) "axxium-codex-dispatch-")))
        intent {:binding binding-id :caller-id caller-id :notice-sha256 (client/notice-sha256 notice)}
        queue-row {"id" "queue-original" "clientUserMessageId" caller-id "input" input}
        completed {"id" "turn-completed" "status" "completed" "itemsView" "full"
                   "items" [{"type" "userMessage" "id" "item-notice" "clientId" caller-id "content" input}]}
        queued (capture [queue-row] [])
        retired (capture [] [completed])]
    (try
      (testing "a read never fabricates a saved intent"
        (is (= {:disposition :hold :reason :missing-intent}
               (client/reconcile-capture! directory caller-id approved-reader queued)))
        (store/prepare! directory intent)
        (is (= {:disposition :hold :reason :not-attempted}
               (client/reconcile-capture! directory caller-id approved-reader queued))))
      (testing "conserve the one attempt, then reconcile without writing"
        (is (true? (:permit? (store/begin-attempt! directory intent))))
        (is (false? (:permit? (store/begin-attempt! directory intent))))
        (is (= {:disposition :accepted :queue-id "queue-original"}
               (client/reconcile-capture! directory caller-id approved-reader queued)))
        (is (= :attempted (:state (store/recover! directory caller-id))))
        (store/record-accepted! directory intent "queue-original")
        (is (= {:disposition :hold :reason :queue-id-mismatch}
               (client/reconcile-capture! directory caller-id approved-reader
                                          (capture [(assoc queue-row "id" "queue-other")] [])))))
      (testing "retirement requires trusted matching evidence and remains advisory"
        (is (= :hold
               (:disposition (client/reconcile-capture! directory caller-id approved-reader
                                                       (assoc queued :reader-after (assoc approved-reader :identity "other"))))))
        (is (= {:disposition :retire :turn-id "turn-completed" :item-id "item-notice"}
               (client/reconcile-capture! directory caller-id approved-reader retired)))
        (is (= :accepted (:state (store/recover! directory caller-id))))
        (is (false? (:permit? (store/begin-attempt! directory intent)))))
      (testing "a continued queue read holds the saved attempt and preserves the store"
        (let [queue-page (page 1 "thread/queue/list" {} [(assoc queue-row "clientUserMessageId" "other")])
              continued (assoc retired :queue-pages
                               [(assoc-in queue-page [:response "result" "nextCursor"] "1")
                                (page 3 "thread/queue/list" {"cursor" "1"} [])])
              store-bytes #(into (sorted-map)
                                 (for [file (.readdirSync fs directory)]
                                   [file (.readFileSync fs (.join path directory file) "utf8")]))
              before (store-bytes)]
          (is (= {:disposition :hold :reason :incomplete-queue}
                 (client/reconcile-capture! directory caller-id approved-reader continued)))
          (is (= before (store-bytes)))
          (is (= :accepted (:state (store/recover! directory caller-id))))
          (is (false? (:permit? (store/begin-attempt! directory intent))))))
      (finally
        (.rmSync fs directory #js {:recursive true :force true})))))

(deftest reconcile-a-durable-intent-with-an-exact-native-capture
  (if (= "linux" (.-platform js/process))
    (check-reconciliation!)
    (is (= :invalid-store
           (try
             (client/reconcile-capture! "/fixture" caller-id approved-reader {})
             (catch :default error (:reason (ex-data error))))))))

(deftest apply-only-exact-completed-native-capture
  (when (= "linux" (.-platform js/process))
    (let [directory (.realpathSync fs (.mkdtempSync fs (.join path (.tmpdir os) "axxium-codex-terminal-")))
          intent {:binding binding-id :caller-id caller-id :notice-sha256 (client/notice-sha256 notice)}
          user-item {"type" "userMessage" "id" "item-terminal" "clientId" caller-id "content" input}
          completed {"id" "turn-terminal" "status" "completed" "itemsView" "full" "items" [user-item]}
          terminal-capture (capture [] [completed])
          next-digest (client/notice-sha256 "Separate approved hint")]
      (try
        (store/prepare! directory intent)
        (store/begin-attempt! directory intent)
        (is (= {:disposition :hold :reason :reader-binding-mismatch}
               (client/retire-capture! directory caller-id approved-reader
                                       (dissoc terminal-capture :reader-after) next-digest)))
        (is (= {:disposition :hold :reason :turn-not-completed}
               (client/retire-capture! directory caller-id approved-reader
                                       (capture [] [(assoc completed "status" "failed")]) next-digest)))
        (is (= :attempted (:state (store/recover! directory caller-id))))
        (let [{:keys [successor-intent] :as saved}
              (client/retire-capture! directory caller-id approved-reader terminal-capture next-digest)]
          (is (= :retired (:state saved)))
          (is (= {:turn-id "turn-terminal" :item-id "item-terminal"} (:terminal saved)))
          (is (= next-digest (:notice-sha256 successor-intent)))
          (is (= successor-intent
                 (:intent (store/prepare-successor! directory caller-id binding-id)))))
        (finally
          (.rmSync fs directory #js {:recursive true :force true}))))))
