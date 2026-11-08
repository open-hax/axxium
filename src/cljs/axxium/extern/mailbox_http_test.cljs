(ns axxium.extern.mailbox-http-test
  "Exercise the mailbox with real loopback HTTP and signed session tokens."
  (:require [axxium.auth.session :as session]
            [axxium.auth.token :as token]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.domain.kernel-fixture :as fixture]
            [axxium.extern.agent-credentials :as secrets]
            [axxium.extern.fastify :as fastify]
            [axxium.extern.ledger-file :as disk]
            [axxium.extern.signing-key :as signing]
            [axxium.routes.mailbox :as routes]
            [clojure.string :as str]
            [cljs.test :refer [async deftest is]]
            ["@fastify/cookie" :default fastify-cookie]
            ["node:fs" :as fs]
            ["node:http" :as node-http]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ^:private origin "http://axxium.test")
(def ^:private endpoint "/api/mailbox/mcp")
(def ^:private audience (str origin endpoint))
(def ^:private headers {"host" "axxium.test"
                       "accept" "application/json, text/event-stream"})

(defn- ^:async start-app! []
  (let [app (fastify/create-app)]
    (.register app fastify-cookie)
    (routes/register-mailbox-routes! app)
    {:app app :url (str (await (.listen app #js {:port 0 :host "127.0.0.1"})) endpoint)}))

(defn- request! [app method token sid body overrides]
  (let [request-headers (merge headers
                               (when (some? body) {"content-type" "application/json"})
                               (when token {"authorization" (str "Bearer " token)})
                               (when sid {"mcp-session-id" sid "mcp-protocol-version" "2025-06-18"})
                               overrides)
        payload (if (string? body) body (when (some? body) (js/JSON.stringify (clj->js body))))]
    (js/Promise.
     (fn [resolve reject]
       (let [req (node-http/request
                  (:url app) (clj->js {:method method :headers request-headers})
                  (fn [response]
                    (let [chunks (atom [])]
                      (.setEncoding response "utf8")
                      (.on response "data" #(swap! chunks conj %))
                      (.on response "error" reject)
                      (.on response "end"
                           (fn []
                             (try
                               (let [text (apply str @chunks)]
                                 (resolve {:status (.-statusCode response)
                                           :sid (aget (.-headers response) "mcp-session-id")
                                           :data (when (seq text) (js->clj (js/JSON.parse text)))
                                           :text text}))
                               (catch :default error (reject error))))))))]
         (.on req "error" reject)
         (.end req payload))))))

(defn- rpc [method]
  {"jsonrpc" "2.0" "id" 1 "method" method})

(defn- tool [name arguments]
  (assoc (rpc "tools/call") "params" {"name" name "arguments" arguments}))

(defn- result [response]
  (get-in response [:data "result" "structuredContent"]))

(defn- ^:async mint-fixture! [live actor cred-id did]
  (let [bearer (await (token/create-atproto-token! {:id actor} cred-id did))
        query (db/q-select-actor-by-atproto-session actor (secrets/token-hash bearer) cred-id did)]
    (swap! live assoc query {:id actor})
    {:bearer bearer :query query}))

(defn- ^:async verify-http* []
  (let [keys (await (signing/generate-ephemeral!))
        dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-mailbox-http-"))
        ledger (path/join dir "ledger.edn")
        live (atom {})
        app (atom nil)
        config cfg/get-in-config]
    (try
      (fs/writeFileSync ledger "" #js {:mode 384})
      (with-redefs [signing/current! (constantly keys)
                    db/query-one-sql (fn [q] (js/Promise.resolve (get @live q)))
                    cfg/get-in-config (fn [ks]
                                        (case (first ks)
                                          :mailbox/ledger-file ledger
                                          :axxium/public-base-url origin
                                          (config ks)))]
        (reset! app (await (start-app!)))
        (let [load-events (:load! (disk/private-ledger-storage ledger []))
              alice-id "actor_11111111-1111-4111-8111-111111111111"
              bob-id "actor_22222222-2222-4222-8222-222222222222"
              ordinary (await (token/create-token {:id alice-id} (secrets/secure-id)))
              alice (await (mint-fixture! live alice-id (secrets/secure-id) fixture/alice))
              bob (await (mint-fixture! live bob-id (secrets/secure-id) fixture/bob))
              other-alice (await (mint-fixture! live alice-id (secrets/secure-id) fixture/alice))
              a (:bearer alice)
              b (:bearer bob)
              init (rpc "initialize")]
          (is (= 401 (:status (await (request! @app "POST" nil nil init {})))))
          (is (= 401 (:status (await (request! @app "POST" ordinary nil init {})))))
          (is (= 403 (:status (await (request! @app "POST" a nil init {"origin" "https://elsewhere.test"})))))
          (is (= 403 (:status (await (request! @app "POST" a nil init {"host" "localhost"})))))
          (is (= 401 (:status (await (request! @app "POST" nil nil init {"cookie" (str session/COOKIE-NAME "=" a)})))))
          (is (= 401 (:status (await (request! @app "POST" "invalid" nil init
                                              {"origin" origin "cookie" (str session/COOKIE-NAME "=" a)})))))
          (let [started (await (request! @app "POST" nil nil init
                                         {"origin" origin "cookie" (str session/COOKIE-NAME "=" a)}))
                a-session (:sid started)
                b-session (:sid (await (request! @app "POST" b nil init {})))]
            (is (= 200 (:status started)))
            (is (and a-session b-session (not= a-session b-session)))
            (is (= "2025-06-18" (get-in started [:data "result" "protocolVersion"])))
            (let [initialized (await (request! @app "POST" a a-session
                                                {"jsonrpc" "2.0" "method" "notifications/initialized"} {}))]
              (is (= 202 (:status initialized)))
              (is (= "" (:text initialized))))
            (is (= 400 (:status (await (request! @app "POST" a a-session (rpc "tools/list")
                                                 {"mcp-protocol-version" "0.0.0"})))))
            (is (= 400 (:status (await (request! @app "POST" a a-session init {})))))
            (is (= 400 (:status (await (request! @app "POST" a nil (rpc "tools/list") {})))))
            (is (= 404 (:status (await (request! @app "POST" (:bearer other-alice) a-session (rpc "tools/list") {})))))
            (is (= 404 (:status (await (request! @app "POST" b a-session (rpc "tools/list") {})))))
            (is (= 405 (:status (await (request! @app "GET" a a-session nil {})))))
            (is (= 405 (:status (await (request! @app "DELETE" a a-session nil {})))))
            (is (some #(= "send_message" (get % "name"))
                      (get-in (await (request! @app "POST" a a-session (rpc "tools/list") {}))
                              [:data "result" "tools"])))
            (let [poll (result (await (request! @app "POST" b b-session
                                               (tool "open_session" {"audience" audience "ttl_ms" 10000 "push" true})
                                               {})))]
              (is (= "poll" (get poll "mode")))
              (is (= b-session (get poll "id")))
              (is (= 2 (count (filter #(= :axxium.session/opened (:event/type %))
                                      (load-events ledger))))))
            (await (request! @app "POST" a a-session (tool "register_mailbox" {}) {}))
            (await (request! @app "POST" b b-session (tool "register_mailbox" {}) {}))
            (await (request! @app "POST" b b-session (tool "grant_send" {"to" fixture/alice}) {}))
            (let [candidate (tool "send_message" {"to" fixture/bob "idempotency_key" "invalid-value"
                                                 "body" {"nested" ["number-placeholder"]}})
                  encoded (js/JSON.stringify (clj->js candidate))]
              (doseq [invalid [(str/replace encoded "\"number-placeholder\"" "1e400")
                               (str/replace encoded "\"number-placeholder\"" "9007199254740993")
                               (assoc-in candidate ["params" "arguments" "body" "nested"]
                                         [(js/String.fromCharCode 0xD800)])
                               (assoc-in candidate ["params" "arguments" "idempotency_key"]
                                         (js/String.fromCharCode 0xD800))]]
                (is (true? (get-in (await (request! @app "POST" a a-session invalid {}))
                                   [:data "result" "isError"]))))
              (is (empty? (filter #(= :axxium.mailbox/accepted (:event/type %))
                                  (load-events ledger)))))
            (let [send (tool "send_message" {"to" fixture/bob "idempotency_key" "wire"
                                             "body" {"payload_key" {"nested" "durable"}}})
                  rejected (await (request! @app "POST" a a-session (-> send
                                                        (dissoc "id")
                                                        (assoc-in ["params" "arguments" "idempotency_key"] "forbidden-notification"))
                                                    {}))
                  sent (result (await (request! @app "POST" a a-session send {})))
                  replay (result (await (request! @app "POST" a a-session send {})))
                  before (result (await (request! @app "POST" b b-session (tool "read_inbox" {}) {})))]
              (is (= 400 (:status rejected)))
              (is (= "accepted" (get sent "accepted")))
              (is (= sent replay))
              (is (= 1 (count (get before "messages"))))
              (is (= {"payload_key" {"nested" "durable"}}
                     (get-in before ["messages" 0 "message_body"])))
              (await (.close (:app @app)))
              (reset! app nil)
              (reset! app (await (start-app!)))
              (let [after (result (await (request! @app "POST" b b-session
                                                   (tool "read_inbox" {"after" (get before "next_cursor")}) {})))]
                (is (= (get before "messages") (get after "messages"))))
              (let [message-id (get sent "message_id")
                    ack (tool "acknowledge_message" {"message_id" message-id})]
                (is (true? (get-in (await (request! @app "POST" a a-session ack {}))
                                   [:data "result" "isError"])))
                (is (= message-id (get (result (await (request! @app "POST" b b-session ack {})))
                                        "message_id"))))
              (swap! live dissoc (:query alice))
              (is (= 401 (:status (await (request! @app "POST" a a-session send {})))))
              (is (= 1 (count (filter #(= :axxium.mailbox/accepted (:event/type %))
                                      (load-events ledger)))))
              (let [saved (str ledger ".saved")
                    replacement (path/join dir "replacement.edn")]
                (fs/renameSync ledger saved)
                (is (= 503 (:status (await (request! @app "POST" b b-session (rpc "tools/list") {})))))
                (is (not (fs/existsSync ledger)) "missing ledger was not recreated")
                (fs/writeFileSync replacement "" #js {:mode 8r600})
                (fs/symlinkSync replacement ledger)
                (is (= 503 (:status (await (request! @app "POST" b b-session (rpc "tools/list") {})))))
                (is (= "" (fs/readFileSync replacement "utf8")) "symlink target was not modified"))))))

      (finally
        (when @app (await (.close (:app @app))))
        (fs/rmSync dir #js {:recursive true})))))

(deftest authenticated-loopback-mailbox-polls-and-recovers
  (async done
    (-> (verify-http*)
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
