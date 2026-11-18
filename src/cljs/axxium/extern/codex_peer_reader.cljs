(ns axxium.extern.codex-peer-reader
  "Bounded read-only Codex Desktop RPC over an independently pinned local peer."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:http" :refer [Agent]]
            ["node:net" :refer [Socket]]
            ["ws" :refer [WebSocket]]
            [axxium.extern.token-crypto :as token-crypto]
            [clojure.string :as str]
            [promesa.core :as p]))

(def ^:private capture-ms 20000)
(def ^:private connect-ms 5000)
(def ^:private max-executable-bytes (* 512 1024 1024))
(def ^:private max-frame-bytes (* 2 1024 1024))
(def ^:private max-capture-bytes (* 8 1024 1024))
(def ^:private max-frames 256)
(def ^:private max-pages 16)
(def ^:private page-entries 50)
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

(defn- account-frame! [state bytes]
  (let [{:keys [bytes-read frames-read]}
        (swap! state #(-> % (update :bytes-read + bytes) (update :frames-read inc)))]
    (when (or (> bytes-read max-capture-bytes) (> frames-read max-frames))
      (refuse! :rpc-capture-limit))))

(defn- response! [{:keys [state deadline]} data binary?]
  (remaining-ms! deadline)
  (when (or binary? (not (js/Buffer.isBuffer data)))
    (refuse! :invalid-rpc-frame))
  (account-frame! state (.-length data))
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
                                :maxPayload max-frame-bytes
                                :maxBufferedChunks 1024
                                :maxFragments 1024
                                :maxHeaderSize 8192
                                :handshakeTimeout handshake-ms})
            state (atom {:bytes-read 0 :frames-read 0 :next-id 0})
            rpc {:ws ws :agent agent :state state :deadline deadline}
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
        (.on ws "error" #(reject-session! rpc (failure :native-connection-error)))
        (.on ws "close" #(reject-session! rpc (failure :native-connection-closed)))
        (.on ws "message"
             (fn [bytes binary?]
               (try
                 (response! rpc bytes binary?)
                 (catch :default error (reject-session! rpc error)))))
        (doseq [event ["ping" "pong"]]
          (.on ws event
               (fn [bytes]
                 (try
                   (account-frame! state (.-length bytes))
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

(defn- ensure-open! [{:keys [state ws deadline]}]
  (remaining-ms! deadline)
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
                (when-not (and (vector? data) (<= (count data) page-entries)
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
                      (read-page pages (conj cursors next-cursor) next-cursor history-items)))))))]
    (read-page [] #{} nil 0)))

(defn- collect! [rpc addon handle config deadline]
  (let [params {"threadId" (get-in config [:binding :thread-id]) "limit" page-entries}
        reader {:identity (token-crypto/stable-digest "codex-peer-reader/v1" (mapv config pin-keys))
                :binding (:binding config)}]
    (p/let [_ (:opened rpc)
            _ (peer! addon handle)
            _ (rpc! rpc "initialize" {"clientInfo" {"name" "axxium-codex-peer-reader" "version" "1"}
                                     "capabilities" {"experimentalApi" true}})]
      (ensure-open! rpc)
      (.send (:ws rpc) "{\"method\":\"initialized\"}")
      (p/let [queue (read-pages! rpc "thread/queue/list" params)
              turns (read-pages! rpc "thread/turns/list" (assoc params "itemsView" "full"))]
        (ensure-open! rpc)
        (authenticate! addon handle config deadline)
        {:approved-reader reader
         :evidence {:reader-before reader :reader-after reader
                    :queue-pages queue :turn-pages turns}}))))

(defn- observe-connected! [addon handle config deadline]
  (authenticate! addon handle config deadline)
  (let [socket (use-verified-fd! (.takeSocketFd addon handle))]
    (-> (p/let [_ (js/Promise.resolve nil)
                rpc (open-rpc! socket deadline)]
          (-> (p/let [_ (js/Promise.resolve nil)] (collect! rpc addon handle config deadline))
              (.finally #(close-rpc! rpc))))
        (.finally #(.destroy socket)))))

(defn capture!
  "Return paired native queue and full-turn pages for the exact approved binding.

   Both the addon and config come from independent host custody. The only
   connection is the peer-guarded Unix descriptor and the only RPCs are
   initialization and bounded reads. Pass the in-memory capture to the
   dispatch observation law and retain only its normalized evidence."
  [addon config]
  (let [deadline (+ (js/performance.now) capture-ms)]
    (p/let [_ (js/Promise.resolve nil)]
      (bind-and-pin! config)
      (let [{:keys [socket-path uid gid pid socket-device socket-inode]} config
            handle (.openPeer addon socket-path uid gid pid socket-device socket-inode
                              (min connect-ms (remaining-ms! deadline)))]
        (-> (p/let [_ (js/Promise.resolve nil)]
              (observe-connected! addon handle config deadline))
            (.finally #(.closePeer addon handle)))))))
