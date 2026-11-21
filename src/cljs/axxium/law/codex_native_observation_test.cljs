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

(def ^:private anchor ["completed" "turn-before-attempt"])
(def ^:private anchor-turn (assoc turn "id" (second anchor) "items" []))

(defn- window-page [id view cursor rows next-cursor]
  (let [options (cond-> {"itemsView" view "limit" 1} cursor (assoc "cursor" cursor))
        turns (if (= "notLoaded" view)
                (mapv #(assoc % "itemsView" "notLoaded" "items" []) rows)
                rows)]
    (rpc-page id "thread/turns/list" options turns next-cursor)))

(defn- window-evidence [pages]
  {:reader-before approved-reader :reader-after approved-reader
   :queue-pages [(queue-page [])] :window-pages pages
   :window-head (-> (:metadata (first pages))
                     (assoc-in [:request "id"] "head-recheck")
                     (assoc-in [:response "id"] "head-recheck"))})

(defn- project-window [saved-anchor reads]
  (native/normalize (assoc intent :history-anchor saved-anchor) approved-reader reads digest-text))

(def ^:private anchored-window
  [{:metadata (window-page 2 "notLoaded" nil [turn] "anchor-cursor")
    :full (window-page 3 "full" nil [turn] "anchor-cursor")}
   {:metadata (window-page 4 "notLoaded" "anchor-cursor" [anchor-turn] "old-history")
    :full nil}])

(deftest pre-attempt-anchor-requires-an-empty-queue-and-idle-history
  (let [queue (queue-page [])
        empty-history (window-page 2 "notLoaded" nil [] nil)
        completed (window-page 2 "notLoaded" nil [anchor-turn] "past-anchor")
        read-anchor #(native/read-history-anchor "thread-a" %1 %2)]
    (is (= ["empty"] (read-anchor queue empty-history)))
    (is (= anchor (read-anchor queue completed)))
    (doseq [invalid [nil "empty" ["empty" nil] ["completed"] ["completed" "   "]
                     ["completed" (apply str (repeat 513 "x"))] ["future" "turn-a"]]]
      (is (false? (native/history-anchor? invalid))))
    (doseq [[invalid-queue invalid-turns]
            [[(queue-page [queue-row]) completed]
             [(assoc-in queue [:request "params" "cursor"] "other-page") completed]
             [(assoc-in queue [:response "result" "nextCursor"] "other-page") completed]
             [(assoc-in queue [:request "params" "threadId"] "other-thread") completed]
             [queue (window-page 1 "notLoaded" nil [anchor-turn] nil)]
             [queue (window-page 2 "full" nil [anchor-turn] nil)]
             [queue (window-page 2 "notLoaded" "other-page" [anchor-turn] nil)]
             [queue (window-page 2 "notLoaded" nil [(assoc anchor-turn "status" "inProgress")] nil)]
             [queue (assoc-in completed [:request "params" "sortDirection"] "asc")]
             [queue (window-page 2 "notLoaded" nil [] "empty-window-cannot-progress")]
             [queue (update-in empty-history [:response "result"] dissoc "nextCursor")]]]
      (is (nil? (read-anchor invalid-queue invalid-turns))))))

(deftest saved-anchor-bounds-full-history-to-newer-turns
  (let [reads (window-evidence anchored-window)
        result (:observation (project-window anchor reads))
        at-anchor (window-evidence [{:metadata (window-page 2 "notLoaded" nil [anchor-turn] "unread")
                                     :full nil}])]
    (is (= {:disposition :retire :turn-id "turn-a" :item-id "item-a"}
           (dispatch/reconcile intent result)))
    (is (= {:disposition :hold :reason :no-positive-evidence}
           (dispatch/reconcile intent (:observation (project-window anchor at-anchor)))))
    (is (= {:disposition :accepted :queue-id "queue-a"}
           (dispatch/reconcile intent (:observation
                                       (project-window anchor (assoc at-anchor :queue-pages [(queue-page [queue-row])]))))))
    (is (= {:error :unsupported-native-history} (project reads)))
    (is (= {:error :unsupported-native-history}
           (project-window anchor (assoc reads :turn-pages [(turn-page [turn])]))))
    (is (= {:error :invalid-intent} (project-window nil reads)))
    (is (= {:error :incomplete-queue}
           (project-window anchor (update-in reads [:queue-pages 0 :response "result" "nextCursor"] (constantly "more")))))))

(deftest anchored-history-holds-incomplete-or-changing-evidence
  (let [reads (window-evidence anchored-window)
        window #(assoc-in reads [:window-pages %1 %2] %3)
        repeat-rpc-id (-> reads
                          (assoc-in [:window-head :request "id"] 4)
                          (assoc-in [:window-head :response "id"] 4))]
    (doseq [bad [(dissoc reads :window-head)
                 (assoc reads :window-pages (pop anchored-window))
                 (assoc reads :window-pages (conj anchored-window (second anchored-window)))
                 (assoc reads :window-head (window-page 5 "notLoaded" nil [(assoc turn "id" "new-head")] "anchor-cursor"))
                 (assoc-in reads [:window-head :response "result" "nextCursor"] "changed-cursor")
                 (window 0 :full nil)
                 (window 0 :full (window-page 3 "full" "unexpected" [turn] "anchor-cursor"))
                 (window 0 :full (window-page 3 "full" nil [turn] "different-continuation"))
                 (window 0 :full (window-page 3 "full" nil [(assoc turn "status" "inProgress")] "anchor-cursor"))
                 (window 0 :full (window-page 3 "full" nil [(assoc turn "id" "different-turn")] "anchor-cursor"))
                 (assoc-in reads [:window-pages 1 :metadata :response "result" "data" 0 "items"] [user-item])
                 (window 1 :metadata (window-page 4 "notLoaded" "other-cursor" [anchor-turn] nil))
                 (window 1 :metadata (window-page 4 "notLoaded" "anchor-cursor" [turn] nil))
                 (window 1 :metadata (window-page 4 "notLoaded" "anchor-cursor" [anchor-turn] "anchor-cursor"))
                 (window 1 :metadata (window-page 4 "notLoaded" "anchor-cursor" [(assoc anchor-turn "status" "failed")] nil))
                 (window 1 :full (window-page 5 "full" "anchor-cursor" [anchor-turn] "old-history"))]]
      (is (= {:error :incomplete-history} (project-window anchor bad))))
    (is (= {:error :invalid-native-evidence} (project-window anchor repeat-rpc-id)))))

(deftest empty-anchor-requires-complete-history
  (let [reads (window-evidence [{:metadata (window-page 2 "notLoaded" nil [] nil) :full nil}])
        continuation {:metadata (window-page 2 "notLoaded" nil [turn] "tail")
                      :full (window-page 3 "full" nil [turn] "tail")}
        duplicate-turn (assoc turn "id" "turn-b")
        one-turn {:metadata (window-page 2 "notLoaded" nil [turn] nil)
                  :full (window-page 3 "full" nil [turn] nil)}
        two-turns [continuation {:metadata (window-page 4 "notLoaded" "tail" [duplicate-turn] nil)
                                 :full (window-page 5 "full" "tail" [duplicate-turn] nil)}]]
    (is (= [] (get-in (project-window ["empty"] reads) [:observation :history])))
    (is (= {:disposition :retire :turn-id "turn-a" :item-id "item-a"}
           (dispatch/reconcile intent (:observation (project-window ["empty"] (window-evidence [one-turn]))))))
    (is (= {:error :incomplete-history}
           (project-window ["empty"] (window-evidence [continuation]))))
    (is (= {:error :incomplete-history}
           (project-window ["empty"] (window-evidence [{:metadata (window-page 2 "notLoaded" nil [] "empty-tail")
                                                        :full nil}]))))
    (is (= {:error :incomplete-history}
           (project-window ["empty"]
                           (window-evidence [continuation {:metadata (window-page 4 "notLoaded" "tail" [] nil)
                                                           :full nil}]))))
    (is (= {:disposition :hold :reason :duplicate-caller}
           (dispatch/reconcile intent (:observation (project-window ["empty"] (window-evidence two-turns))))))))

(deftest anchored-history-keeps-page-and-item-budgets
  (let [window (fn [total]
                 (mapv (fn [index]
                         (let [cursor (when (pos? index) (str "cursor-" index))
                               following (when (< (inc index) total) (str "cursor-" (inc index)))
                               newer (assoc turn "id" (str "newer-" index) "items" [])
                               metadata-id (+ 2 (* index 2))]
                           {:metadata (window-page metadata-id "notLoaded" cursor [newer] following)
                            :full (window-page (inc metadata-id) "full" cursor [newer] following)}))
                       (range total)))
        items (mapv (fn [index] {"id" (str "item-" index) "type" "agentMessage"}) (range 4097))
        large-turn (assoc-in (window 1) [0 :full :response "result" "data" 0 "items"] items)]
    (is (some? (:observation (project-window ["empty"] (window-evidence (window 16))))))
    (is (= {:error :incomplete-history}
           (project-window ["empty"] (window-evidence (window 17)))))
    (is (= {:error :invalid-native-evidence}
           (project-window ["empty"] (window-evidence large-turn))))))
