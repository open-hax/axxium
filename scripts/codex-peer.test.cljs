#_{:clj-kondo/ignore [:namespace-name-mismatch]}
(ns axxium.extern.codex-peer-fixture
  "Explicit Linux qualification against private synthetic app-server sockets."
  (:require ["node:assert/strict" :as assert]
            ["node:child_process" :as child-process]
            ["node:crypto" :as crypto]
            ["node:events" :as events]
            ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:module" :as module]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:readline" :as readline]
            ["node:url" :as url]
            ["ws" :as ws]
            [axxium.extern.codex-dispatch-client :as dispatch]
            [axxium.extern.codex-intent-store :as store]
            [axxium.extern.codex-peer-reader :as reader]
            [axxium.law.codex-native-observation :as observations]
            [promesa.core :as p]))

(def ^:private binding-id
  {:actor-did "did:web:fixture.example"
   :device-id "fixture-device"
   :thread-id "fixture-thread"
   :generation "fixture-generation"})

(def ^:private notice "fixture queue notice")
(def ^:private caller-id "fixture-caller")
(def ^:private queue-method "thread/queue/list")
(def ^:private turns-method "thread/turns/list")
(def ^:private rpc-methods ["initialize" "initialized" queue-method queue-method turns-method turns-method])
(def ^:private timeout-ms 8000)
(def ^:private inbox-change
  {"jsonrpc" "2.0" "method" "notifications/resources/updated"
   "params" {"uri" "axxium://mailbox/inbox"}})

(defn- sha256 [value]
  (-> (crypto/createHash "sha256") (.update value) (.digest "hex")))

(defn- socket-pin [socket-path]
  (let [stat (fs/lstatSync socket-path #js {:bigint true})]
    {:socket-path socket-path
     :socket-device (.toString (.-dev stat))
     :socket-inode (.toString (.-ino stat))}))

(defn- fixture-config [socket-path peer]
  (merge {:binding binding-id
          :uid (.getuid js/process)
          :gid (.getgid js/process)
          :pid (.-pid js/process)
          :exec-sha256 (sha256 (fs/readFileSync (.-execPath js/process)))}
         peer
         (socket-pin socket-path)))

(defn- other-pin [value]
  (if (= value "1") "2" "1"))

(defn- queue-row [id]
  {"id" id "clientUserMessageId" "unrelated-caller" "input" []})

(defn- turn-row [id]
  {"id" id "status" "completed" "itemsView" "full"
   "items" (if (= id "fixture-turn")
             [{"id" "fixture-item" "type" "userMessage" "clientId" caller-id
               "content" [{"type" "text" "text" notice "text_elements" []}]}]
             [])})

(defn- paginated-reply [method params]
  (assert/strictEqual (get params "threadId") (:thread-id binding-id))
  (when (= turns-method method)
    (assert/strictEqual (get params "itemsView") "full"))
  (let [cursor (get params "cursor")
        queue? (= queue-method method)]
    (assert/strictEqual (boolean (pos-int? (get params "limit"))) true)
    (if (nil? cursor)
      {"data" [(if queue? (queue-row "other-queue") (turn-row "other-turn"))]
       "nextCursor" (str method "/second")}
      (do
        (assert/strictEqual cursor (str method "/second"))
        {"data" [(if queue? (queue-row "second-queue") (turn-row "fixture-turn"))]
         "nextCursor" nil}))))

(defn- fixture-reply [method params]
  (case method
    "initialize" {"userAgent" "codex-app-server/fixture" "codexHome" "/fixture"
                  "platformFamily" "unix" "platformOs" "linux"}
    ("thread/queue/list" "thread/turns/list") (paginated-reply method params)
    (throw (ex-info "Unexpected fixture method" {:method method}))))

(defn- socket-pair! [client message result]
  (.send client (js/JSON.stringify
                 (clj->js {"jsonrpc" "2.0" "id" (get message "id") "result" result}))))

(defn- send-progress! [client]
  (let [progress (js/JSON.stringify
                  #js {:jsonrpc "2.0" :method "fixture/progress"
                       :params #js {:text (.repeat "a" (* 1792 1024))}})]
    (letfn [(send-next [remaining]
              (when (and (pos? remaining) (= ws/WebSocket.OPEN (.-readyState client)))
                (.send client progress (fn [error]
                                         (when-not error (send-next (dec remaining)))))))]
      (send-next 5))))

(defn- install-rpc-fixture! [client socket-path mode calls errors]
  (.on client "error" (fn [_] nil))
  (.on client "message"
       (fn [bytes binary?]
         (try
           (assert/strictEqual binary? false)
           (let [message (js->clj (js/JSON.parse (.toString bytes "utf8")))
                 method (get message "method")
                 params (get message "params")]
             (swap! calls conj method)
             (if (= method "initialized")
               (assert/strictEqual (contains? message "id") false)
               (do
                 (when (and (= mode :rename-socket) (= method queue-method) (nil? (get params "cursor")))
                   (fs/renameSync socket-path (str socket-path ".displaced")))
                 (when (and (= mode :exit-peer) (= method queue-method))
                   (.exit js/process 0))
                 (cond
                   (and (= mode :server-request) (= method queue-method))
                   (.send client "{\"id\":99,\"method\":\"fixture/server-request\"}")

                   (and (= mode :stream-limit) (= method queue-method))
                   (send-progress! client)

                   :else
                   (let [result (fixture-reply method params)]
                     (socket-pair! client
                                   (if (and (= mode :wrong-response) (= method queue-method))
                                     (update message "id" inc)
                                     message)
                                   (if (fn? mode) (mode method params result) result)))))))
           (catch :default error
             (swap! errors conj error)
             (.terminate client))))))

(defn- start-fixture! [socket-path mode]
  (let [server (http/createServer)
        sockets (atom #{})
        ws-server (new ws/WebSocketServer #js {:noServer true})
        connections (atom 0)
        upgrades (atom [])
        calls (atom [])
        errors (atom [])
        socket-events (events/EventEmitter.)
        first-socket-closed (events/once socket-events "disconnected")
        listening (events/once server "listening")]
    (.on server "connection" (fn [socket]
                               (let [first-socket? (= 1 (swap! connections inc))]
                                 (swap! sockets conj socket)
                                 (.once socket "close"
                                        (fn []
                                          (swap! sockets disj socket)
                                          (when first-socket?
                                            (.emit socket-events "disconnected")))))))
    (.on server "upgrade"
         (fn [request socket head]
           (swap! upgrades conj (.-url request))
           (if (= mode :redirect)
             (.end socket (str "HTTP/1.1 302 Found\r\nLocation: /redirected\r\n"
                               "Connection: close\r\nContent-Length: 0\r\n\r\n"))
             (.handleUpgrade ws-server request socket head
                             #(install-rpc-fixture! % socket-path mode calls errors)))))
    (.listen server socket-path)
    (p/let [_ listening]
      (fs/chmodSync socket-path 384)
      {:server server :ws-server ws-server :sockets sockets :calls calls
       :connections connections :upgrades upgrades :errors errors
       :first-socket-closed first-socket-closed})))

(defn- close-fixture! [{:keys [server ws-server sockets]}]
  (doseq [socket @sockets] (.destroy socket))
  (.close ws-server)
  (js/Promise. (fn [resolve reject]
                 (.close server (fn [error] (if error (reject error) (resolve nil)))))))

(defn- private-directory []
  (let [prefix (path/join (fs/realpathSync (os/tmpdir)) "axxium-peer-")
        directory (fs/mkdtempSync prefix)]
    (assert/strictEqual (bit-and (.-mode (fs/lstatSync directory)) 511) 448)
    directory))

(defn- remove-directory! [directory]
  (fs/rmSync directory #js {:recursive true :force true}))

(defn- in-fixture! [mode run]
  (let [directory (private-directory)
        socket-path (path/join directory "reader.sock")]
    (p/finally
     (p/let [fixture (start-fixture! socket-path mode)]
       (p/finally (p/then (p/resolved nil)
                          (fn [_] (run fixture (fixture-config socket-path {}))))
                  (fn [] (close-fixture! fixture))))
     (fn [] (remove-directory! directory)))))

(defn- refusal-reason [error]
  (loop [cause error depth 0]
    (when (and cause (< depth 5))
      (or (:reason (ex-data cause)) (.-code cause)
          (recur (or (ex-cause cause) (.-cause cause)) (inc depth))))))

(defn- expect-refusal! [run reason]
  (p/let [outcome (p/catch (p/then (p/resolved nil) (fn [_] (run)))
                          (fn [error] {:refusal-reason (refusal-reason error)}))
          actual (:refusal-reason outcome)
          expected (if (set? reason) reason #{reason})]
    (assert/ok (contains? expected actual)
               (str "Expected refusal " (pr-str expected) ", saw " (pr-str actual)))))

(defn- assert-equal [actual expected]
  (assert/ok (= actual expected)
             (str "Fixture expected " (pr-str expected) ", saw " (pr-str actual))))

(defn- same-socket-capture! [addon]
  (in-fixture!
   :ordinary
   (fn [{:keys [connections upgrades calls errors first-socket-closed]} config]
     (p/let [{:keys [approved-reader evidence]} (reader/capture! addon config)
             _ (p/timeout first-socket-closed timeout-ms)
             normalized (observations/normalize
                         {:binding binding-id :caller-id caller-id}
                         approved-reader evidence sha256)]
       (assert-equal (:binding approved-reader) binding-id)
       (assert-equal (:reader-before evidence) approved-reader)
       (assert-equal (:reader-after evidence) approved-reader)
       (assert/strictEqual (count (:queue-pages evidence)) 2)
       (assert/strictEqual (count (:turn-pages evidence)) 2)
       (assert-equal @calls rpc-methods)
       (assert/strictEqual @connections 1)
       (assert/strictEqual (count @upgrades) 1)
       (assert/strictEqual (count @errors) 0)
       (assert-equal
        (:observation normalized)
        {:binding binding-id :queue-complete? true :history-complete? true
         :queue []
         :history [{:turn-id "fixture-turn" :item-id "fixture-item" :caller-id caller-id
                    :status :completed :notice-sha256 (sha256 notice)}]})
       (println "PASS same verified Unix connection, exact read-only RPCs, and normalized capture")))))

(defn- inbox-intake! [addon]
  (let [directory (private-directory)
        intent {:binding binding-id :caller-id caller-id
                :notice-sha256 (dispatch/notice-sha256 notice)}
        trigger (atom nil)
        pass (atom 0)
        mode (fn [method params reply]
               (when (and (= method queue-method) (nil? (get params "cursor"))
                          (= 1 (swap! pass inc)))
                 (@trigger)
                 (@trigger))
               (if (and (= method turns-method) (= 1 @pass))
                 (update reply "data" #(mapv (fn [row] (assoc row "items" [])) %))
                 reply))]
    (store/prepare! directory intent)
    (p/finally
     (p/let [_ (in-fixture!
                :wrong-response
                (fn [{:keys [connections]} config]
                  (let [worker (dispatch/inbox-intake directory caller-id addon config)
                        notify #(worker binding-id inbox-change)]
                    (p/let [prepared (notify)
                            _ (assert-equal prepared {:disposition :hold :reason :not-attempted})
                            _ (assert-equal @connections 0)
                            _ (assert-equal (:permit? (store/begin-attempt! directory intent)) true)
                            _ (expect-refusal! notify :unexpected-rpc-id)
                            _ (expect-refusal! notify :unexpected-rpc-id)]
                      (assert-equal @connections 2)
                      (assert-equal (:state (store/recover! directory caller-id)) :attempted)))))
             _ (in-fixture!
                mode
                (fn [{:keys [connections calls]} config]
                  (let [worker (dispatch/inbox-intake directory caller-id addon config)
                        notify #(worker binding-id inbox-change)]
                    (p/let [foreign (worker (assoc binding-id :generation "other-generation") inbox-change)
                            wrong-resource (worker binding-id
                                                   (assoc-in inbox-change ["params" "uri"] "other"))
                            request (worker binding-id (assoc inbox-change "id" 0))
                            _ (doseq [result [foreign wrong-resource request]]
                                (assert-equal result {:disposition :ignored}))
                            _ (assert-equal @connections 0)
                            _ (reset! trigger notify)
                            result (let [running (notify)]
                                     (assert-equal (identical? running (notify)) true)
                                     running)
                            recovered (notify)]
                      (assert-equal @connections 2)
                      (assert-equal @calls (into rpc-methods rpc-methods))
                      (assert-equal result recovered)
                      (assert-equal (:state recovered) :retired)
                      (assert-equal (:successor-intent recovered) nil)
                      (assert-equal (:terminal recovered) {:turn-id "fixture-turn" :item-id "fixture-item"})))))]
       (println "PASS bound inbox hints, single-flight reads, and verified terminal checkpoint"))
     (fn [] (remove-directory! directory)))))

(defn- wrong-pins! [addon]
  (in-fixture!
   :ordinary
   (fn [{:keys [upgrades calls errors]} config]
     (let [cases [[:socket-device (other-pin (:socket-device config)) "AXXIUM_PEER_PATH"]
                  [:socket-inode (other-pin (:socket-inode config)) "AXXIUM_PEER_PATH"]
                  [:uid (inc (:uid config)) "AXXIUM_PEER_CREDENTIALS"]
                  [:gid (inc (:gid config)) "AXXIUM_PEER_CREDENTIALS"]
                  [:pid (inc (:pid config)) "AXXIUM_PEER_CREDENTIALS"]
                  [:exec-sha256 (apply str (repeat 64 "0")) :peer-executable-mismatch]]]
       (p/let [_ (reduce (fn [previous [key value reason]]
                          (p/then previous
                                  (fn [_]
                                    (expect-refusal! #(reader/capture! addon (assoc config key value)) reason))))
                        (p/resolved nil) cases)]
         (assert-equal @upgrades [])
         (assert-equal @calls [])
         (assert-equal @errors [])
         (println "PASS wrong device, inode, uid, gid, pid and binary digest before RPC"))))))

(defn- refused-stream! [addon mode reason expected-calls]
  (in-fixture!
   mode
   (fn [{:keys [connections upgrades calls errors first-socket-closed]} config]
     (p/let [_ (expect-refusal! #(reader/capture! addon config) reason)
             _ (p/timeout first-socket-closed timeout-ms)]
       (assert/strictEqual @connections 1)
       (assert/strictEqual (count @upgrades) 1)
       (assert-equal (if (= mode :rename-socket) (take (count expected-calls) @calls) @calls)
                     expected-calls)
       (assert-equal @errors [])))))

(defn- reject-after-connection! [addon]
  (let [first-read ["initialize" "initialized" queue-method]]
    (p/let [_ (refused-stream! addon :rename-socket "AXXIUM_PEER_PATH" first-read)
            _ (refused-stream! addon :wrong-response :unexpected-rpc-id first-read)
            _ (refused-stream! addon :server-request :unexpected-rpc-request first-read)
            _ (refused-stream! addon :stream-limit :rpc-stream-limit first-read)
            _ (refused-stream! addon :redirect :native-connection-error [])]
      (println "PASS path drift, wrong reply, server request, stream limit and redirect with socket cleanup"))))

(defn- child-ready! [child]
  (js/Promise.
   (fn [resolve reject]
     (let [output (readline/createInterface #js {:input (.-stdout child) :crlfDelay js/Infinity})
           timeout (js/setTimeout #(reject (ex-info "Synthetic peer startup timed out" {})) timeout-ms)]
       (.once child "error" (fn [error] (js/clearTimeout timeout) (reject error)))
       (.once child "exit" (fn [code]
                            (js/clearTimeout timeout)
                            (reject (ex-info "Synthetic peer exited before startup" {:code code}))))
       (.once output "line"
              (fn [line]
                (js/clearTimeout timeout)
                (.close output)
                (try
                  (resolve (js->clj (js/JSON.parse line)))
                  (catch :default error (reject error)))))))))

(defn- peer-exit! [addon]
  (let [directory (private-directory)
        socket-path (path/join directory "reader.sock")
        child (child-process/spawn (.-execPath js/process)
                                   #js [(aget (.-argv js/process) 1) "-cp" "src/cljs"
                                        "scripts/codex-peer.test.cljs" "--fixture-peer" socket-path]
                                   #js {:stdio #js ["ignore" "pipe" "inherit"]})
        closed (events/once child "close")]
    (p/finally
     (p/let [ready (child-ready! child)
             pid (get ready "pid")
             _ (assert/strictEqual pid (.-pid child))
             config (fixture-config socket-path {:pid pid})
             _ (expect-refusal! #(reader/capture! addon config)
                                #{:native-connection-closed :native-connection-error})
             result (p/timeout closed timeout-ms)]
       (assert/strictEqual (aget result 0) 0)
       (println "PASS verified peer exit during capture"))
     (fn []
       (when (nil? (.-exitCode child)) (.kill child "SIGKILL"))
       (p/finally closed (fn [] (remove-directory! directory)))))))

(defn- serve-dying-peer! [socket-path]
  (let [directory (path/dirname socket-path)
        stat (fs/lstatSync directory)]
    (assert/ok (and (= "reader.sock" (path/basename socket-path))
                    (= (path/dirname directory) (fs/realpathSync (os/tmpdir)))
                    (.startsWith (path/basename directory) "axxium-peer-")
                    (.isDirectory stat)
                    (= (.-uid stat) (.getuid js/process))
                    (= (bit-and (.-mode stat) 511) 448))
               "Synthetic child peer requires its private temporary directory"))
  (p/let [_ (start-fixture! socket-path :exit-peer)]
    (println (js/JSON.stringify #js {:pid (.-pid js/process)}))))

(defn- run-reader-checks! [addon-path]
  (assert/strictEqual (.-platform js/process) "linux" "Linux kernel peer checks are required")
  (assert/ok addon-path "Pass the compiled codex_peer.node as the first argument")
  (let [load-addon (module/createRequire (url/pathToFileURL (path/resolve "package.json")))
        addon (load-addon (path/resolve addon-path))]
    (p/let [_ (same-socket-capture! addon)
            _ (inbox-intake! addon)
            _ (wrong-pins! addon)
            _ (reject-after-connection! addon)
            _ (peer-exit! addon)]
      (println "PASS Linux Codex peer reader fixture"))))

(let [[mode argument] *command-line-args*]
  (-> (p/then (p/resolved nil)
              (fn [_]
                (if (= mode "--fixture-peer")
                  (serve-dying-peer! argument)
                  (run-reader-checks! mode))))
      (p/catch (fn [error]
                 (js/console.error error)
                 (set! (.-exitCode js/process) 1)))))
