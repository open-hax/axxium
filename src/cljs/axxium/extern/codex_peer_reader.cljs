(ns axxium.extern.codex-peer-reader
  "Bounded read-only Codex Desktop RPC over an independently pinned local peer."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:http" :refer [Agent]]
            ["node:net" :refer [Socket]]
            ["ws" :refer [WebSocket]]
            [axxium.extern.token-crypto :as token-crypto]
            [axxium.law.codex-native-observation :as observations]
            [clojure.string :as str]
            [promesa.core :as p]))

(def ^:private capture-ms 20000)
(def ^:private connect-ms 5000)
(def ^:private max-executable-bytes (* 512 1024 1024))
(def ^:private max-message-bytes (* 2 1024 1024))
(def ^:private max-capture-bytes (* 8 1024 1024))
(def ^:private max-events 256)
(def ^:private max-pages 16)
(def ^:private page-entries 50)
(def ^:private queue-page-entries 100)
(def ^:private max-history-items 4096)
(def ^:private pin-keys
  [:socket-path :uid :gid :pid :socket-device :socket-inode :exec-sha256])

(defn- failure [reason]
  (ex-info "Codex peer reader refused the capture" {:reason reason}))

(defn- refuse! [reason]
  (throw (failure reason)))

(defn- remaining-ms! [deadline]
  (let [remaining (js/Math.ceil (- deadline (js/performance.now)))]
    (when-not (pos? remaining) (refuse! :capture-timeout))
    remaining))

(defn- bounded-text? [value limit]
  (and (string? value) (<= 1 (count value) limit)
       (not (str/blank? value))))

(defn- bind-and-pin! [config]
  (let [{:keys [binding socket-path uid gid pid socket-device socket-inode exec-sha256]} config
        credential? #(and (int? %) (<= 0 % 4294967295))
        decimal-pin? #(and (string? %) (re-matches #"(?:0|[1-9][0-9]{0,19})" %))]
    (when-not (and (= "linux" (.-platform js/process))
                   (map? config) (= (set (conj pin-keys :binding)) (set (keys config)))
                   (map? binding)
                   (= #{:actor-did :device-id :thread-id :generation} (set (keys binding)))
                   (every? #(bounded-text? % 512) (vals binding))
                   (bounded-text? socket-path 107) (str/starts-with? socket-path "/")
                   (not (str/includes? socket-path (js/String.fromCharCode 0)))
                   (<= (js/Buffer.byteLength socket-path "utf8") 107)
                   (credential? uid) (credential? gid) (int? pid) (<= 1 pid 2147483647)
                   (decimal-pin? socket-device) (decimal-pin? socket-inode)
                   (string? exec-sha256) (re-matches #"[a-f0-9]{64}" exec-sha256))
      (refuse! :invalid-peer-config))))

(defn- peer! [addon handle]
  (when-not (true? (.checkPeer addon handle))
    (refuse! :peer-check-failed)))

(defn- stat-identity [stat]
  [(.-size stat) (.-mtimeMs stat) (.-ctimeMs stat)])

(defn- executable-sha256! [fd deadline]
  (let [stat (fs/fstatSync fd)
        size (.-size stat)]
    (when-not (and (.isFile stat) (<= 1 size max-executable-bytes))
      (refuse! :invalid-peer-executable))
    (let [hash (crypto/createHash "sha256")
          buffer (js/Buffer.allocUnsafe (* 128 1024))]
      (loop [position 0]
        (remaining-ms! deadline)
        (when (< position size)
          (let [amount (fs/readSync fd buffer 0 (min (- size position) (.-length buffer)) position)]
            (when-not (pos? amount) (refuse! :peer-executable-changed))
            (.update hash (.subarray buffer 0 amount))
            (recur (+ position amount)))))
      (when-not (= (stat-identity stat) (stat-identity (fs/fstatSync fd)))
        (refuse! :peer-executable-changed))
      (.digest hash "hex"))))

(defn- authenticate! [addon handle {:keys [exec-sha256]} deadline]
  (peer! addon handle)
  (let [fd (.openExecutableFd addon handle)]
    (try
      (when-not (= exec-sha256 (executable-sha256! fd deadline))
        (refuse! :peer-executable-mismatch))
      (finally (fs/closeSync fd))))
  (remaining-ms! deadline)
  (peer! addon handle))

(defn- use-verified-fd! [fd]
  (try
    (Socket. #js {:fd fd :readable true :writable true})
    (catch :default error
      (fs/closeSync fd)
      (throw error))))

(defn- reject-session! [{:keys [state ws]} error]
  (let [{:keys [pending failed? closing?]} @state]
    (when-not (or failed? closing?)
      (swap! state assoc :failed? true :pending nil)
      (when pending ((:reject pending) error))
      (.terminate ws))))

(defn- ensure-stream-size! [socket]
  (when (> (.-bytesRead socket) max-capture-bytes)
    (refuse! :rpc-stream-limit)))

(defn- account-event! [state]
  (when (> (:events (swap! state update :events inc)) max-events)
    (refuse! :rpc-capture-limit)))

(defn- response! [{:keys [state socket deadline]} data binary?]
  (remaining-ms! deadline)
  (ensure-stream-size! socket)
  (when (or binary? (not (js/Buffer.isBuffer data)))
    (refuse! :invalid-rpc-frame))
  (account-event! state)
  (let [frame (try
                (js->clj (js/JSON.parse (.toString data "utf8")))
                (catch :default _ (refuse! :invalid-rpc-json)))
        {:keys [pending]} @state]
    (when-not (and (map? frame) (= "2.0" (get frame "jsonrpc" "2.0")))
      (refuse! :invalid-rpc-frame))
    (if (contains? frame "method")
      (when-not (and (bounded-text? (get frame "method") 512)
                     (not-any? #(contains? frame %) ["id" "result" "error"]))
        (refuse! :unexpected-rpc-request))
      (do
        (when-not (and (:id pending) (= (:id pending) (get frame "id")))
          (refuse! :unexpected-rpc-id))
        (when (contains? frame "error") (refuse! :native-rpc-error))
        (when-not (map? (get frame "result")) (refuse! :invalid-rpc-result))
        (swap! state assoc :pending nil)
        ((:resolve pending) frame)))))

(defn- open-rpc! [socket deadline]
  (let [handshake-ms (min connect-ms (remaining-ms! deadline))
        agent (Agent. #js {:keepAlive false :maxSockets 1})
        used? (atom false)
        single-connection (fn [_options _callback]
                            (when-not (compare-and-set! used? false true)
                              (refuse! :second-connection))
                            socket)]
    (set! (.-createConnection agent) single-connection)
    (try
      (let [ws (WebSocket. "ws://localhost/"
                           #js {:agent agent
                                :createConnection (fn [& _] (refuse! :unverified-connection))
                                :lookup (fn [& _] (refuse! :unverified-connection))
                                :followRedirects false
                                :perMessageDeflate false
                                :maxPayload max-message-bytes
                                :maxBufferedChunks 1024
                                :maxFragments 1024
                                :maxHeaderSize 8192
                                :handshakeTimeout handshake-ms})
            state (atom {:events 0 :next-id 0})
            rpc {:ws ws :socket socket :agent agent :state state :deadline deadline}
            opened (js/Promise.
                    (fn [resolve reject]
                      (swap! state assoc :pending {:reject reject})
                      (.once ws "open"
                             (fn []
                               (when-not (:failed? @state)
                                 (swap! state assoc :pending nil)
                                 (resolve nil))))))
            timeout (js/setTimeout #(reject-session! rpc (failure :capture-timeout))
                                   (max 0 (js/Math.ceil (- deadline (js/performance.now)))))]
        (.prependListener socket "data"
                          (fn [_bytes]
                            (try
                              (ensure-stream-size! socket)
                              (catch :default error (reject-session! rpc error)))))
        (.on ws "error" #(reject-session! rpc (failure :native-connection-error)))
        (.on ws "close" #(reject-session! rpc (failure :native-connection-closed)))
        (.on ws "message"
             (fn [bytes binary?]
               (try
                 (response! rpc bytes binary?)
                 (catch :default error (reject-session! rpc error)))))
        (doseq [event ["ping" "pong"]]
          (.on ws event
               (fn [_bytes]
                 (try
                   (account-event! state)
                   (catch :default error (reject-session! rpc error))))))
        (assoc rpc :opened opened :timeout timeout))
      (catch :default error
        (.destroy agent)
        (throw error)))))

(defn- close-rpc! [{:keys [agent state timeout ws]}]
  (swap! state assoc :closing? true :pending nil)
  (js/clearTimeout timeout)
  (try
    (.terminate ws)
    (finally (.destroy agent))))

(defn- ensure-open! [{:keys [state ws socket deadline]}]
  (remaining-ms! deadline)
  (ensure-stream-size! socket)
  (when (or (:failed? @state) (not= 1 (.-readyState ws)))
    (refuse! :native-connection-closed)))

(defn- rpc! [{:keys [state ws] :as rpc} method params]
  (ensure-open! rpc)
  (let [id (:next-id (swap! state update :next-id inc))
        request {"id" id "method" method "params" params}]
    (js/Promise.
     (fn [resolve reject]
       (swap! state assoc :pending
              {:id id :reject reject :resolve #(resolve {:request request :response %})})
       (try
         (.send ws (js/JSON.stringify (clj->js request))
                (fn [error]
                  (when error (reject-session! rpc (failure :native-send-failed)))))
         (catch :default _ (reject-session! rpc (failure :native-send-failed))))))))

(defn- count-history-items! [rows]
  (transduce
   (map (fn [row]
          (let [items (get row "items")]
            (when-not (vector? items) (refuse! :invalid-native-page))
            (count items))))
   + rows))

(defn- read-pages! [rpc method params]
  (letfn [(read-page [pages cursors cursor history-items]
            (p/let [page (rpc! rpc method (cond-> params cursor (assoc "cursor" cursor)))]
              (let [result (get-in page [:response "result"])
                    data (get result "data")
                    next-cursor (get result "nextCursor")
                    pages (conj pages page)]
                (when-not (and (vector? data) (<= (count data) (get params "limit"))
                               (contains? result "nextCursor"))
                  (refuse! :invalid-native-page))
                (let [history-items (if (= method "thread/turns/list")
                                      (+ history-items (count-history-items! data))
                                      history-items)]
                  (when (> history-items max-history-items) (refuse! :native-page-limit))
                  (if (nil? next-cursor)
                    pages
                    (do
                      (when-not (and (bounded-text? next-cursor 4096) (seq data)
                                     (< (count pages) max-pages) (not (contains? cursors next-cursor)))
                        (refuse! :native-page-limit))
                      ;; Preserve a queue continuation for the observation law.
                      ;; A second live-offset read cannot establish coverage.
                      (if (= method "thread/queue/list")
                        pages
                        (read-page pages (conj cursors next-cursor) next-cursor history-items))))))))]
    (read-page [] #{} nil 0)))

(defn- read-queue! [rpc thread-id]
  (read-pages! rpc "thread/queue/list" {"threadId" thread-id "limit" queue-page-entries}))

(defn- window-page! [rpc thread-id cursor items-view]
  (p/let [page (rpc! rpc "thread/turns/list"
                     (cond-> {"threadId" thread-id "limit" 1 "itemsView" items-view}
                       cursor (assoc "cursor" cursor)))]
    (let [{:strs [data] next-cursor "nextCursor" :as result} (get-in page [:response "result"])]
      (when-not (and (vector? data) (<= (count data) 1) (contains? result "nextCursor"))
        (refuse! :invalid-native-page))
      (when (and (some? next-cursor)
                 (not (and (seq data) (bounded-text? next-cursor 4096))))
        (refuse! :native-page-limit))
      page)))

(defn- read-window! [rpc thread-id [_ anchor-turn]]
  (letfn [(read-next [pages cursors cursor history-items]
            (p/let [metadata (window-page! rpc thread-id cursor "notLoaded")]
              (let [{:strs [data] next-cursor "nextCursor"} (get-in metadata [:response "result"])
                    {turn-id "id" status "status"} (first data)]
                (cond
                  (and anchor-turn (= anchor-turn turn-id))
                  (do
                    (when-not (= "completed" status)
                      (refuse! :history-anchor-unavailable))
                    (conj pages {:metadata metadata :full nil}))

                  (and (nil? anchor-turn) (empty? pages) (empty? data))
                  [{:metadata metadata :full nil}]

                  (or (empty? data) (and anchor-turn (nil? next-cursor)))
                  (refuse! :history-anchor-unavailable)

                  :else
                  (p/let [full (window-page! rpc thread-id cursor "full")]
                    (let [rows (get-in full [:response "result" "data"])
                          history-items (+ history-items (count-history-items! rows))
                          pages (conj pages {:metadata metadata :full full})]
                      (when (> history-items max-history-items)
                        (refuse! :native-page-limit))
                      (if (nil? next-cursor)
                        pages
                        (do
                          (when (or (contains? cursors next-cursor) (>= (count pages) max-pages))
                            (refuse! :native-page-limit))
                          (read-next pages (conj cursors next-cursor) next-cursor history-items)))))))))]
    (read-next [] #{} nil 0)))

(defn- collect! [rpc addon handle config deadline read!]
  (let [thread-id (get-in config [:binding :thread-id])
        reader {:identity (token-crypto/stable-digest "codex-peer-reader/v1" (mapv config pin-keys))
                :binding (:binding config)}]
    (p/let [_ (:opened rpc)
            _ (peer! addon handle)
            _ (rpc! rpc "initialize" {"clientInfo" {"name" "axxium-codex-peer-reader" "version" "1"}
                                     "capabilities" {"experimentalApi" true}})]
      (ensure-open! rpc)
      (.send (:ws rpc) "{\"method\":\"initialized\"}")
      (p/let [observation (read! rpc thread-id)]
        (ensure-open! rpc)
        (authenticate! addon handle config deadline)
        {:approved-reader reader
         :observation observation}))))

(defn- observe-connected! [addon handle config deadline read!]
  (authenticate! addon handle config deadline)
  (let [socket (use-verified-fd! (.takeSocketFd addon handle))]
    (-> (p/let [_ (js/Promise.resolve nil)
                rpc (open-rpc! socket deadline)]
          (-> (p/let [_ (js/Promise.resolve nil)] (collect! rpc addon handle config deadline read!))
              (.finally #(close-rpc! rpc))))
        (.finally #(.destroy socket)))))

(defn- observe! [addon config read!]
  (let [deadline (+ (js/performance.now) capture-ms)]
    (p/let [_ (js/Promise.resolve nil)]
      (bind-and-pin! config)
      (let [{:keys [socket-path uid gid pid socket-device socket-inode]} config
            handle (.openPeer addon socket-path uid gid pid socket-device socket-inode
                              (min connect-ms (remaining-ms! deadline)))]
        (-> (p/let [_ (js/Promise.resolve nil)]
              (observe-connected! addon handle config deadline read!))
            (.finally #(.closePeer addon handle)))))))

(defn- capture-evidence! [addon config read!]
  (p/let [{:keys [approved-reader observation]} (observe! addon config read!)]
    {:approved-reader approved-reader
     :evidence (assoc observation :reader-before approved-reader :reader-after approved-reader)}))

(defn capture!
  "Return paired read-only RPC pages for the exact approved binding.

   Both the addon and config come from independent host custody. Evidence
   for an anchored attempt contains the bounded window up to its saved
   predecessor. An older attempt still requires full history coverage.
   Pass the in-memory capture to the dispatch observation law."
  ([addon config]
   (capture-evidence!
    addon config
    (fn [rpc thread-id]
      (p/let [queue (read-queue! rpc thread-id)
              turns (read-pages! rpc "thread/turns/list" {"threadId" thread-id "limit" page-entries
                                                         "itemsView" "full"})]
        {:queue-pages queue :turn-pages turns}))))
  ([addon config history-anchor]
   (when-not (observations/history-anchor? history-anchor)
     (refuse! :invalid-history-anchor))
   (capture-evidence!
    addon config
    (fn [rpc thread-id]
      (p/let [queue (read-queue! rpc thread-id)
              pages (read-window! rpc thread-id history-anchor)
              head (window-page! rpc thread-id nil "notLoaded")]
        {:queue-pages queue :window-pages pages :window-head head})))))

(defn capture-anchor!
  "Observe a pre-attempt history anchor on the authenticated Desktop peer.

   Requires an empty complete initial queue and a newest completed turn or
   empty history. The result describes a read, not a native dispatch permit."
  [addon config]
  (p/let [{:keys [approved-reader observation]}
          (observe!
           addon config
           (fn [rpc thread-id]
             (p/let [queue (read-queue! rpc thread-id)
                     head (window-page! rpc thread-id nil "notLoaded")]
               (observations/read-history-anchor thread-id (first queue) head))))]
    (when-not observation
      (refuse! :history-anchor-unavailable))
    {:approved-reader approved-reader :history-anchor observation}))
