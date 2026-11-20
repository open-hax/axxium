(ns axxium.law.codex-native-observation-test
  (:require [axxium.law.codex-dispatch :as dispatch]
            [axxium.law.codex-native-observation :as native]
            [cljs.test :refer [deftest is]]))

(def ^:private binding-id {:actor-did "did:web:fixture.example:codex"
                           :device-id "device-a"
                           :thread-id "thread-a"
                           :generation "generation-a"})
(def ^:private approved-reader {:identity "fixture-verified-peer-a" :binding binding-id})
(def ^:private notice "Notice α\n Preserve whitespace. ")
(def ^:private digest "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def ^:private other-digest "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
(def ^:private intent {:binding binding-id :caller-id "saved-caller" :notice-sha256 digest})
(def ^:private content [{"type" "text" "text" notice "text_elements" []}])

(def ^:private queue-row
  {"id" "queue-a" "clientUserMessageId" "saved-caller" "input" content
   "responsesapiClientMetadata" {"opaque" "irrelevant-metadata"}})

(def ^:private user-item
  {"type" "userMessage" "id" "item-a" "clientId" "saved-caller" "content" content})

(def ^:private turn
  {"id" "turn-a" "items" [user-item] "itemsView" "full"
   "status" "completed" "completedAt" nil})

(defn- rpc-page [id method options rows next-cursor]
  {:request {"id" id "method" method
             "params" (merge {"threadId" "thread-a" "limit" 50} options)}
   :response {"id" id "result" {"data" rows "nextCursor" next-cursor}}})

(defn- queue-page [rows]
  (rpc-page 1 "thread/queue/list" {} rows nil))

(defn- turn-page [rows]
  (rpc-page 2 "thread/turns/list" {"itemsView" "full"} rows nil))

(defn- evidence [queued turns]
  {:reader-before approved-reader :reader-after approved-reader
   :queue-pages [(queue-page queued)] :turn-pages [(turn-page turns)]})

(defn- digest-text [text]
  (if (= notice text) digest other-digest))

(defn- project [reads]
  (native/normalize intent approved-reader reads digest-text))

(deftest complete-native-evidence-normalizes-for-dispatch
  (let [queued (:observation (project (evidence [queue-row] [])))
        unrelated [(assoc user-item "id" "unbound" "clientId" nil)
                   (assoc (dissoc user-item "clientId")
                          "id" "missing-caller" "content" [{"type" "image"}])
                   {"id" "agent-a" "type" "agentMessage" "text" "unrelated"}]
        turns [(assoc turn "items" (into [user-item] unrelated))]
        completed (:observation (project (evidence [] turns)))]
    (is (= {:binding binding-id :queue-complete? true :history-complete? true
            :queue [{:queue-id "queue-a" :caller-id "saved-caller" :notice-sha256 digest}]
            :history []}
           queued))
    (is (= [{:turn-id "turn-a" :item-id "item-a" :status :completed
             :caller-id "saved-caller" :notice-sha256 digest}]
           (:history completed)))
    (is (= {:disposition :accepted :queue-id "queue-a"} (dispatch/reconcile intent queued)))
    (is (= {:disposition :retire :turn-id "turn-a" :item-id "item-a"}
           (dispatch/reconcile intent completed))))
  (is (= {:disposition :hold :reason :no-positive-evidence}
         (dispatch/reconcile intent
                             (:observation (project (evidence [] [(assoc turn "items" [(dissoc user-item "clientId")])])))))))

(deftest queue-read-requires-one-initial-terminal-page
  (let [other-row (assoc queue-row "id" "queue-b" "clientUserMessageId" "unrelated"
                        "input" [{"type" "image" "url" "fixture:img"}])
        begin (rpc-page 1 "thread/queue/list" {} [other-row] "next")
        last-page (rpc-page 3 "thread/queue/list" {"cursor" "next"} [queue-row] nil)
        wrap #(assoc (evidence [] []) :queue-pages %)]
    (doseq [bad [[begin last-page]
                 [begin]
                 [last-page]
                 [(queue-page []) last-page]
                 [(update-in (queue-page []) [:response "result"] dissoc "nextCursor")]
                 [(assoc-in (queue-page [queue-row other-row]) [:request "params" "limit"] 1)]]]
      (is (= {:error :incomplete-queue} (project (wrap bad)))))))

(deftest wrong-native-read-or-reader-holds
  (let [base (evidence [] [])
        turn-path [:turn-pages 0]
        queue-path [:queue-pages 0]
        replace-path #(assoc-in base (into %1 %2) %3)]
    (doseq [bad [(assoc-in base [:reader-before :identity] "another-instance")
                 (assoc-in base [:reader-after :binding :generation] "retired-generation")
                 (assoc base :reader-after nil)]]
      (is (= {:error :reader-binding-mismatch} (project bad))))
    (doseq [[path value] [[["params" "threadId"] "different-thread"]
                          [["method"] "turn/start"]]]
      (is (= {:error :incomplete-queue} (project (replace-path queue-path (into [:request] path) value)))))
    (doseq [bad [(replace-path turn-path [:response "id"] 12)
                 (replace-path turn-path [:response "error"] {"code" -32601})
                 (replace-path turn-path [:request "params" "itemsView"] "summary")
                 (replace-path turn-path [:response "result" "nextCursor"] "missing-tail")]]
      (is (= {:error :incomplete-history} (project bad))))
    (is (= {:error :unsupported-native-history} (project (assoc base :item-pages [{}]))))
    (is (= {:error :invalid-native-evidence}
           (project (-> base
                        (assoc-in (into turn-path [:request "id"]) 1)
                        (assoc-in (into turn-path [:response "id"]) 1)))))))

(deftest numeric-rpc-identities-must-round-trip-exactly
  (let [reads #(assoc (evidence [] []) :queue-pages
                      [(rpc-page % "thread/queue/list" {} [] nil)])]
    (is (some? (:observation (project (reads 9007199254740991)))))
    (is (some? (:observation (project (reads "9007199254740992")))))
    (is (= {:error :incomplete-queue} (project (reads 9007199254740992))))
    (is (= {:error :incomplete-queue} (project (reads -9007199254740992))))))

(deftest incomplete-content-and-provenance-never-retire
  (let [partial (assoc turn "itemsView" "summary")
        unknown (assoc turn "status" "unknown")
        malformed (assoc turn "items" [(assoc user-item "clientId" [:unexpected])])
        span-content [(assoc (first content) "text_elements" [{"byteRange" {"start" 0 "end" 1}}])]]
    (doseq [turns [[partial] [unknown] [turn turn] [malformed]
                   [(assoc turn "items" [(assoc user-item "content" span-content)])]
                   [(assoc turn "items" [user-item user-item])]
                   [(assoc turn "items" [(assoc user-item "type" "unsupported")])]]]
      (is (= {:error :invalid-native-evidence} (project (evidence [] turns)))))
    (doseq [[native-status expected] [["failed" :failed] ["inProgress" :in-progress]
                                     ["interrupted" :interrupted]]]
      (let [result (:observation (project (evidence [] [(assoc turn "status" native-status)])))]
        (is (= expected (get-in result [:history 0 :status])))
        (is (= {:disposition :hold :reason :turn-not-completed}
               (dispatch/reconcile intent result)))))
    (is (= {:error :invalid-native-evidence}
           (native/normalize intent approved-reader (evidence [queue-row] [])
                             (fn [_] (throw (ex-info "digest unavailable" {}))))))
    (is (= {:error :invalid-native-evidence}
           (native/normalize (assoc intent :queue-id "queue-a") approved-reader
                             (evidence [(assoc queue-row "clientUserMessageId" "different")] [turn])
                             digest-text)))
    (is (= {:disposition :hold :reason :notice-mismatch}
           (dispatch/reconcile intent (:observation (project (evidence [(assoc queue-row "input" [{"type" "text" "text" "different"}])] []))))))))

(deftest native-read-size-bounds
  (let [rows (mapv (fn [index]
                     (assoc queue-row "id" (str "queue-" index)
                            "clientUserMessageId" (str "caller-" index)))
                   (range 129))
        turn-pages (fn [total]
                     (mapv (fn [index]
                             (rpc-page (+ 100 index) "thread/turns/list"
                                       {"itemsView" "full"
                                        "cursor" (when (pos? index) (str "page-" index))}
                                       [(assoc turn "id" (str "turn-" index) "items" [])]
                                       (when (< (inc index) total) (str "page-" (inc index)))))
                           (range total)))
        single (fn [page-limit entries]
                 [(rpc-page 1 "thread/queue/list" {"limit" page-limit} entries nil)])
        items (mapv (fn [index] {"id" (str "item-" index) "type" "agentMessage"})
                    (range 4097))
        native-reads #(project (assoc (evidence [] []) :queue-pages %))]
    (is (some? (:observation (project (assoc (evidence [] []) :turn-pages (turn-pages 16))))))
    (is (= {:error :incomplete-history}
           (project (assoc (evidence [] []) :turn-pages (turn-pages 17)))))
    (is (some? (:observation (native-reads (single 128 (pop rows))))))
    (is (= {:error :incomplete-queue} (native-reads (single 129 rows))))
    (is (= {:error :incomplete-queue} (native-reads (single 128 rows))))
    (doseq [limit [nil false "50" [] 1.5 0]]
      (is (= {:error :incomplete-queue} (native-reads (single limit [])))))
    (is (some? (:observation (project (evidence [] [(assoc turn "items" (pop items))])))))
    (is (= {:error :invalid-native-evidence}
           (project (evidence [] [(assoc turn "items" items)]))))
    (is (= {:error :invalid-native-evidence}
           (project (evidence [] [(assoc turn "items" false)]))))))
