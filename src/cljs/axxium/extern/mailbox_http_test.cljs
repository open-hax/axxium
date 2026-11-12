(ns axxium.extern.mailbox-http-test
  "Exercise the mailbox with real loopback HTTP and signed session tokens."
  (:require [axxium.auth.session :as session]
            [axxium.auth.token :as token]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.domain.github-identity :as gi]
            [axxium.domain.kernel-fixture :as fixture]
            [axxium.domain.state :as state]
            [axxium.extern.agent-credentials :as secrets]
            [axxium.extern.fastify :as fastify]
            [axxium.extern.github-identity-mcp :as mcp]
            [axxium.extern.http :as http]
            [axxium.extern.ledger-file :as disk]
            [axxium.extern.mailbox-webhook :as webhook]
            [axxium.extern.signing-key :as signing]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.github-identity :as identity-law]
            [axxium.routes.github-identity :as identity]
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

(defn- ^:async start-app!
  ([] (await (start-app! nil)))
  ([oauth]
   (let [app (fastify/create-app)]
     (.register app fastify-cookie)
     (routes/register-mailbox-routes! app oauth)
     {:app app :url (str (await (.listen app #js {:port 0 :host "127.0.0.1"})) endpoint)})))

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
                                           :challenge (aget (.-headers response) "www-authenticate")
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
                    db/query-auth-proof! (fn [q] (js/Promise.resolve (get @live q)))
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
            (is (= {"tools" {}} (get-in started [:data "result" "capabilities"])))
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
                  before-response (await (request! @app "POST" b b-session (tool "read_inbox" {}) {}))
                  before (result before-response)
                  text-content (get-in before-response [:data "result" "content" 0 "text"])]
              (is (= 400 (:status rejected)))
              (is (= "accepted" (get sent "accepted")))
              (is (= sent replay))
              (is (= 1 (count (get before "messages"))))
              (is (= before (js->clj (js/JSON.parse text-content))))
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

(defn- ^:async verify-webhook* []
  (let [keys (await (signing/generate-ephemeral!))
        dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-mailbox-events-"))
        ledger (path/join dir "ledger.edn")
        key-file (path/join dir "webhook.key")
        live (atom {})
        app (atom nil)
        tick (atom nil)
        mailbox-grant (atom nil)
        access-token "fixture-mailbox-oauth-access-token"
        oauth {:issuer (str origin "/oidc") :resource audience :scope "mailbox:events"
               :access-proof! (fn [value] (when (= access-token value) @mailbox-grant))
               :active-grant? (fn [proof] (= proof @mailbox-grant))}
        challenge-ok? (atom false)
        challenge-count (atom 0)
        on-delivery (atom nil)
        deliveries (atom [])
        config cfg/get-in-config
        callback "https://callback.example.com/inbox"
        secret (str "whsec_" (.toString (js/Buffer.alloc 32 7) "base64"))
        filter-args {"name" "message.available" "arguments" {}
                     "delivery" {"mode" "webhook" "url" callback}}
        subscribe (assoc (rpc "events/subscribe") "params"
                         (assoc-in filter-args ["delivery" "secret"] secret))
        unsubscribe (assoc (rpc "events/unsubscribe") "params" filter-args)
        verify (fn []
                 (swap! challenge-count inc)
                 (js/Promise.resolve {:verified? @challenge-ok? :reason "challenge_failed"}))
        deliver (fn ^:async fake-callback [event]
                  (swap! deliveries conj event)
                  (when-let [f @on-delivery] (await (f)))
                  {:accepted? true :status 202 :response-request-id "req_work_fixture"})]
    (try
      (fs/writeFileSync ledger "" #js {:mode 384})
      (fs/writeFileSync key-file (.toString (js/Buffer.alloc 32 8) "base64") #js {:mode 384})
      (with-redefs [signing/current! (constantly keys)
                    db/query-auth-proof! (fn [q] (js/Promise.resolve (get @live q)))
                    http/on-interval! (fn [_ _ f] (reset! tick f))
                    webhook/verify! (fn ([_] (verify)) ([_ _] (verify)))
                    webhook/deliver! (fn ([_ event] (deliver event)) ([_ _ event] (deliver event)))
                    cfg/get-in-config (fn [ks]
                                        (case (first ks)
                                          :mailbox/ledger-file ledger
                                          :mailbox/webhook-key-file key-file
                                          :mailbox/webhook-actor-did fixture/bob
                                          :mailbox/pilot-allowed-dids #{fixture/alice fixture/bob}
                                          :axxium/public-base-url origin
                                          (config ks)))]
        (reset! app (await (start-app! oauth)))
        (let [alice (await (mint-fixture! live "actor_11111111-1111-4111-8111-111111111111"
                                          (secrets/secure-id) fixture/alice))
              bob (await (mint-fixture! live "actor_22222222-2222-4222-8222-222222222222"
                                        (secrets/secure-id) fixture/bob))
              carol (await (mint-fixture! live "actor_33333333-3333-4333-8333-333333333333"
                                          (secrets/secure-id) fixture/carol))
              a (:bearer alice)
              b (:bearer bob)
              root (await (session/atproto-session-proof b))
              rejected (await (request! @app "POST" (:bearer carol) nil (rpc "initialize") {}))
              ledger-after-rejection (fs/readFileSync ledger "utf8")
              initialized-a (await (request! @app "POST" a nil (rpc "initialize") {}))
              initialized-b (await (request! @app "POST" b nil (rpc "initialize") {}))
              a-session (:sid initialized-a)
              b-session (:sid initialized-b)
              event-call (fn [body] (request! @app "POST" b b-session body {}))
              send (fn [key] (request! @app "POST" a a-session
                                        (tool "send_message" {"to" fixture/bob "idempotency_key" key
                                                              "body" {"private_content" "fixture"}}) {}))]
          (is (= 403 (:status rejected)))
          (is (nil? (:sid rejected)))
          (is (= "" ledger-after-rejection) "An active outsider does not initialize a mailbox session.")
          (is (= {"tools" {}} (get-in initialized-a [:data "result" "capabilities"])))
          (is (= {"tools" {} "events" {}} (get-in initialized-b [:data "result" "capabilities"])))
          (let [before (fs/readFileSync ledger "utf8")
                outsider-call (await (request! @app "POST" (:bearer carol) a-session (tool "register_mailbox" {}) {}))]
            (is (= 403 (:status outsider-call)))
            (is (= before (fs/readFileSync ledger "utf8"))))
          (reset! mailbox-grant (assoc root :oauth/grant-id "fixture-grant" :oauth/client-id "chatgpt-test"
                                           :oauth/resource audience))
          (let [metadata-path "/.well-known/oauth-protected-resource/api/mailbox/mcp"
                metadata-app (update @app :url #(str/replace % (re-pattern (str endpoint "$")) metadata-path))
                missing (await (request! @app "POST" nil nil (rpc "initialize") {}))
                metadata (await (request! metadata-app "GET" nil nil nil {}))
                initialized (await (request! @app "POST" access-token nil (rpc "initialize") {}))
                oauth-session (:sid initialized)]
            (is (= 401 (:status missing)))
            (is (= (str "Bearer resource_metadata=\"" origin metadata-path "\"") (:challenge missing)))
            (is (= [audience ["mailbox:events"] [(str origin "/oidc")]]
                   (mapv (:data metadata) ["resource" "scopes_supported" "authorization_servers"])))
            (is (= {"tools" {} "events" {}} (get-in initialized [:data "result" "capabilities"])))
            (is (= ["whoami"]
                   (mapv #(get % "name") (get-in (await (request! @app "POST" access-token oauth-session
                                                      (rpc "tools/list") {}))
                                                [:data "result" "tools"]))))
            (is (= -32001 (get-in (await (request! @app "POST" access-token oauth-session
                                           (tool "read_inbox" {}) {})) [:data "error" "code"])))
            (is (= 404 (:status (await (request! @app "POST" b oauth-session (rpc "tools/list") {})))))
            (reset! mailbox-grant nil)
            (is (= 401 (:status (await (request! @app "POST" access-token oauth-session (rpc "tools/list") {}))))))
          (is (= [] (get-in (await (event-call (rpc "events/list"))) [:data "result" "events"])))
          (is (= -32601 (get-in (await (request! @app "POST" a a-session subscribe {}))
                                [:data "error" "code"])))
          (is (= -32001 (get-in (await (event-call subscribe)) [:data "error" "code"])))
          (is (zero? @challenge-count))
          (await (request! @app "POST" b b-session (tool "register_mailbox" {}) {}))
          (is (= "message.available" (get-in (await (event-call (rpc "events/list")))
                                              [:data "result" "events" 0 "name"])))
          (await (request! @app "POST" b b-session (tool "grant_send" {"to" fixture/alice}) {}))
          (is (= "accepted" (get (result (await (send "pre-subscription"))) "accepted")))
          (is (= -32015 (get-in (await (event-call subscribe)) [:data "error" "code"])))
          (reset! challenge-ok? true)
          (let [sub (get-in (await (event-call subscribe)) [:data "result"])
                before (fs/readFileSync ledger "utf8")
                refreshed (get-in (await (event-call subscribe)) [:data "result"])]
            (is (str/starts-with? (get sub "id" "") "sub_"))
            (is (= sub refreshed))
            (is (= before (fs/readFileSync ledger "utf8")) "A duplicate subscribe does not append.")
            (is (= 2 @challenge-count) "The same verified callback and key do not require a repeat challenge."))
          (await (@tick))
          (is (empty? @deliveries) "Existing messages are not replayed on subscribe.")
          (await (.close (:app @app)))
          (reset! app (await (start-app! oauth)))
          (reset! on-delivery
                  (fn ^:async repeat-subscribe []
                    (is (str/starts-with? (get-in (await (event-call subscribe)) [:data "result" "id"] "")
                                          "sub_"))))
          (let [message-id (get (result (await (send "after-restart"))) "message_id")]
            (await (@tick))
            (await (@tick))
            (is (= 1 (count @deliveries)))
            (is (= {"message_id" message-id} (get (first @deliveries) "data")))
            (let [load-events (:load! (disk/private-ledger-storage ledger []))
                  delivered (peek (load-events ledger))]
              (is (= :axxium.outbox/delivered (:event/type delivered)))
              (is (= {:status 202} (get-in delivered [:event/body :outbox/transport-receipt])))
              (is (= "req_work_fixture" (get-in delivered [:event/body :outbox/response-request-id]))))
            (let [receipt (result (await (request! @app "POST" a a-session
                                            (tool "get_delivery" {"message_id" message-id}) {})))]
              (is (= "delivered" (get receipt "notification")))
              (is (= "unknown" (get receipt "execution")))
              (is (false? (get receipt "acknowledged")))
              (is (not (str/includes? (pr-str receipt) "req_work_fixture"))
                  "The response request ID is private to the mailbox ledger.")))
          (swap! live dissoc (:query bob))
          (is (= "accepted" (get (result (await (send "after-revocation"))) "accepted")))
          (await (@tick))
          (is (= 1 (count @deliveries)) "A dead root session cannot deliver.")
          (swap! live assoc (:query bob) {:id "actor_22222222-2222-4222-8222-222222222222"})
          (is (= {} (get-in (await (event-call unsubscribe)) [:data "result"])))
          (await (@tick))
          (is (= 1 (count @deliveries)))
          (let [saved (fs/readFileSync ledger "utf8")]
            (is (not (str/includes? saved callback)))
            (is (not (str/includes? saved secret)))
            (is (not (str/includes? saved (secrets/token-hash b)))))))
      (finally
        (when @app (await (.close (:app @app))))
        (fs/rmSync dir #js {:recursive true})))))

(deftest opted-in-mailbox-webhook-survives-restart-and-rechecks-access
  (async done
    (-> (verify-webhook*)
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(defn- ^:async verify-oauth-inbox*
  [app ledger {:keys [work-login codex-login codex-session foreign-message inbox-checks]}]
  (let [run (fn [bearer sid body] (request! app "POST" bearer sid body {}))
        initialize #(run % nil (rpc "initialize"))
        direct-session (:sid (await (initialize work-login)))
        registration (await (run work-login direct-session (tool "register_mailbox" {})))
        initialized (await (initialize "inbox-access"))
        sid (:sid initialized)
        call (fn [session body] (run "inbox-access" session body))
        older-sid (:sid (await (initialize "owner-access")))
        older-call (fn [body] (run "owner-access" older-sid body))
        rpc-error (fn [response] (get-in response [:data "error" "code"]))
        read-inbox (tool "read_inbox" {})
        ack #(tool "acknowledge_message" {"message_id" %})
        inbox-resource {"uri" "axxium://mailbox/inbox"}
        send #(run codex-login codex-session
                   (tool "send_message" {"to" fixture/alice "idempotency_key" %
                                         "body" {"text" %}}))]
    (is (some? (get (result registration) "mailbox")))
    (is (= {"tools" {}} (get-in initialized [:data "result" "capabilities"])))
    (is (= #{"whoami" "discover_actors" "resolve_address" "send_message"
             "read_inbox" "acknowledge_message"}
           (into #{} (map #(get % "name"))
                 (get-in (await (call sid (rpc "tools/list"))) [:data "result" "tools"]))))
    (let [schemes [{"type" "oauth2" "scopes" ["mailbox:events" "mailbox:actors" "mailbox:inbox"]}]
          descriptors (get-in (await (older-call (rpc "tools/list"))) [:data "result" "tools"])
          own-tools (filter #(contains? #{"read_inbox" "acknowledge_message"} (get % "name")) descriptors)]
      (is (= #{"read_inbox" "acknowledge_message"} (set (map #(get % "name") own-tools))))
      (doseq [descriptor own-tools]
        (is (= schemes (get descriptor "securitySchemes")
               (get-in descriptor ["_meta" "securitySchemes"]))))
      (let [before (fs/readFileSync ledger "utf8")
            challenge (str "Bearer error=\"insufficient_scope\", "
                           "error_description=\"Approve access to your mailbox inbox before retrying.\", "
                           "scope=\"mailbox:events mailbox:actors mailbox:inbox\", "
                           "resource_metadata=\"" origin "/.well-known/oauth-protected-resource" endpoint "\"")]
        (doseq [operation [read-inbox (ack foreign-message)]]
          (let [response (get-in (await (older-call operation)) [:data "result"])]
            (is (true? (get response "isError")))
            (is (= [challenge] (get-in response ["_meta" "mcp/www_authenticate"])))))
        (is (= before (fs/readFileSync ledger "utf8")))))
    (doseq [name ["register_mailbox" "grant_send" "revoke_send" "get_delivery"]]
      (is (= -32001 (rpc-error (await (call sid (tool name {})))))))
    (is (= -32601 (rpc-error (await (call sid (rpc "resources/list"))))))
    (doseq [method ["resources/read" "resources/subscribe"]]
      (is (= -32601 (rpc-error (await (call sid (assoc (rpc method) "params" inbox-resource)))))))
    (is (= 405 (:status (await (request! app "GET" "inbox-access" sid nil {"accept" "text/event-stream"})))))
    (is (= [] (get-in (await (call sid (rpc "events/list"))) [:data "result" "events"])))
    (is (true? (get-in (await (call sid (tool "read_inbox" {"mailbox" fixture/bob})))
                       [:data "result" "isError"])))
    (is (true? (get-in (await (call sid (ack foreign-message))) [:data "result" "isError"])))
    (let [first-id (get (result (await (send "inbox-first"))) "message_id")
          second-id (get (result (await (send "inbox-second"))) "message_id")
          messages (get (result (await (call sid read-inbox))) "messages")
          second-seq (get (second messages) "message_seq")]
      (is (= [first-id second-id] (mapv #(get % "message_id") messages)))
      (is (= [fixture/alice fixture/alice] (mapv #(get % "message_to") messages)))
      (is (= 0 (get (result (await (call sid (ack second-id)))) "cursor")))
      (let [reconnected (:sid (await (initialize "inbox-access")))
            unread (get (result (await (call reconnected read-inbox))) "messages")
            explicit (result (await (call reconnected (tool "read_inbox" {"after" second-seq}))))]
        (is (not= sid reconnected))
        (is (= [first-id] (mapv #(get % "message_id") unread)))
        (is (= 0 (get explicit "cursor")))
        (is (= first-id (get-in explicit ["messages" 0 "message_id"])))
        (doseq [operation [read-inbox (ack first-id)]]
          (let [before (fs/readFileSync ledger "utf8")]
            (reset! inbox-checks 1)
            (is (= 401 (:status (await (call reconnected operation)))))
            (is (= -1 @inbox-checks) "The grant is checked again before the operation commits.")
            (is (= before (fs/readFileSync ledger "utf8")))))
        (is (= 401 (:status (await (call reconnected read-inbox)))))
        (reset! inbox-checks nil)
        (is (= second-seq (get (result (await (call reconnected (ack first-id)))) "cursor")))
        (is (= [] (get (result (await (call reconnected read-inbox))) "messages")))))))

(defn- ^:async verify-actor-messaging* []
  (let [keys (await (signing/generate-ephemeral!))
        dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-mailbox-actors-"))
        ledger (path/join dir "ledger.edn")
        actor-ledger (path/join dir "actors.edn")
        root "did:web:axxium.test:actors:owner"
        work (str root ":work")
        codex (str root ":codex")
        policy {:policy/repos #{} :policy/permissions {} :policy/actions #{} :policy/forbid #{}}
        event-ids (fn [] {:decision-id (secrets/secure-id) :effect-id (secrets/secure-id) :at fixture/t0})
        bindings {fixture/alice work fixture/bob codex}
        ;; Routing reads the ledger bindings, never the env seed directly.
        directory (atom (state/fold (:ledger (gi/seed-bindings
                                              (:ledger (gi/seed-ledger
                                                        (gi/resolve-seed {:root {:id root :name "Owner" :policy policy}
                                                                          :nodes [{:id work :parent root :name "Work" :kind :agent :policy policy}
                                                                                  {:id codex :parent root :name "Codex" :kind :agent :policy policy}]}
                                                                         "axxium.test")
                                                        event-ids))
                                              bindings event-ids))))
        live (atom {})
        app (atom nil)
        config cfg/get-in-config
        scoped? (atom true)
        inbox-checks (atom nil)
        webhook-effects (atom [])
        proof {:principal {:auth/did fixture/alice :auth/method :atproto}
               :oauth/grant-id "fixture" :oauth/client-id "chatgpt-fixture" :oauth/resource audience}
        owner-proof (assoc proof :oauth/grant-id "fixture-owner"
                           :principal {:auth/did work :auth/method :actor-node :auth/mailbox fixture/alice}
                           :mailbox/owner-root-did root :mailbox/bound-actor-did work :mailbox/actors? true)
        inbox-proof (assoc owner-proof :oauth/grant-id "fixture-inbox" :mailbox/inbox? true)
        oauth {:issuer (str origin "/oidc") :resource audience :scope "mailbox:events"
               :scopes ["mailbox:events" "mailbox:actors" "mailbox:inbox"] :actor-messaging? true :inbox? true
               :access-proof! #(case %
                                 "events-only" proof
                                 "actor-access" (cond-> proof @scoped? (assoc :mailbox/actors? true))
                                 "owner-access" owner-proof
                                 "inbox-access" inbox-proof
                                 nil)
               :active-grant? #(cond
                                 (:mailbox/inbox? %)
                                 (or (nil? @inbox-checks) (not (neg? (swap! inbox-checks dec))))

                                 (= "fixture" (:oauth/grant-id %))
                                 (or (not (:mailbox/actors? %)) @scoped?)

                                 :else true)}]
    (try
      (fs/writeFileSync ledger "" #js {:mode 384})
      (with-redefs [signing/current! (constantly keys)
                    db/query-auth-proof! (fn [q] (js/Promise.resolve (get @live q)))
                    http/on-interval! (fn [& _] (swap! webhook-effects conj :worker))
                    webhook/verify! (fn [& _] (swap! webhook-effects conj :challenge))
                    identity/with-current-actor-state! (fn ^:async read-identity [f]
                                                        {:result (await (f @directory))})
                    cfg/get-in-config (fn [ks]
                                        (case (first ks)
                                          :mailbox/ledger-file ledger
                                          :mailbox/actor-bindings bindings
                                          :mailbox/subscriptions-enabled? false
                                          :mailbox/stream-enabled? true
                                          :mailbox/webhook-actor-did fixture/alice
                                          :mailbox/webhook-key-file (path/join dir "unused-webhook.key")
                                          :github-identity/ledger-file actor-ledger
                                          :identity/root-operator-binding {:root-did root}
                                          :axxium/public-base-url origin
                                          (config ks)))]
        (reset! app (await (start-app! oauth)))
        (let [bob (:bearer (await (mint-fixture! live "actor_22222222-2222-4222-8222-222222222222"
                                                (secrets/secure-id) fixture/bob)))
              initialize (fn [bearer] (request! @app "POST" bearer nil (rpc "initialize") {}))
              bob-session (:sid (await (initialize bob)))
              initialized (await (initialize "actor-access"))
              scoped-session (:sid initialized)
              run (fn [bearer sid body] (request! @app "POST" bearer sid body {}))
              call (fn [body] (run "actor-access" scoped-session body))
              event-call (fn [body] (run "events-only" scoped-session body))
              list-names (fn [response] (mapv #(get % "name") (get-in response [:data "result" "tools"])))
              subscribe (assoc (rpc "events/subscribe") "params"
                               {"name" "message.available"
                                "delivery" {"mode" "webhook" "url" "https://callback.example.com/inbox"
                                            "secret" (str "whsec_" (.toString (js/Buffer.alloc 32 7) "base64"))}})
              discover (tool "discover_actors" {})
              send (tool "send_message" {"to" fixture/bob "idempotency_key" "work-fixture"
                                         "body" {"text" "hello"}})]
          (is (= {"tools" {}} (get-in initialized [:data "result" "capabilities"])))
          (let [before (fs/readFileSync ledger "utf8")]
            (is (= [] (get-in (await (call (rpc "events/list"))) [:data "result" "events"])))
            (is (= -32601 (get-in (await (call subscribe)) [:data "error" "code"])))
            (is (= before (fs/readFileSync ledger "utf8")))
            (is (empty? @webhook-effects) "Disabled subscriptions cannot start a worker or callback challenge."))
          (await (run bob bob-session (tool "register_mailbox" {})))
          (is (= ["whoami"] (list-names (await (event-call (rpc "tools/list"))))))
          (is (= #{"whoami" "discover_actors" "resolve_address" "send_message"}
                 (set (list-names (await (call (rpc "tools/list")))))))
          (is (= -32001 (get-in (await (call (tool "read_inbox" {}))) [:data "error" "code"])))
          (is (= -32001 (get-in (await (call (tool "acknowledge_message" {}))) [:data "error" "code"])))
          (is (= -32001 (get-in (await (event-call send)) [:data "error" "code"])))
          (is (= [{"did" codex "name" "Codex" "kind" "agent" "mailbox_did" fixture/bob}]
                 (get (result (await (call discover))) "actors")))
          (let [sent (result (await (call send)))
                alice (:bearer (await (mint-fixture! live "actor_11111111-1111-4111-8111-111111111111"
                                                    (secrets/secure-id) fixture/alice)))]
            (is (= "accepted" (get sent "accepted")))
            (await (verify-oauth-inbox* @app ledger {:work-login alice :codex-login bob
                                                    :codex-session bob-session
                                                    :foreign-message (get sent "message_id")
                                                    :inbox-checks inbox-checks})))
          (is (= "hello" (get-in (result (await (run bob bob-session (tool "read_inbox" {}))))
                                 ["messages" 0 "message_body" "text"])))
          (swap! directory assoc-in [:resources (identity-law/actor-resource-id codex)
                                     :resource/metadata :actor/status] :suspended)
          (is (= [] (get (result (await (call discover))) "actors")))
          (is (true? (get-in (await (call send)) [:data "result" "isError"]))
              "A suspended recipient cannot be recovered through an accepted retry.")
          (swap! directory assoc-in [:resources (identity-law/actor-resource-id codex)
                                     :resource/metadata :actor/status] :active)
          (is (= 1 (count (get (result (await (call discover))) "actors"))))
          (swap! directory assoc-in [:resources (identity-law/binding-resource-id fixture/bob)
                                     :resource/metadata :binding/status] :unbound)
          (is (= [] (get (result (await (call discover))) "actors"))
              "An unbound ledger binding stops routing although the env seed still names it.")
          (reset! scoped? false)
          (is (= ["whoami"] (list-names (await (call (rpc "tools/list"))))))
          (await (.close (:app @app)))
          (reset! app (await (start-app! (assoc oauth :inbox? false))))
          (let [owner-sid (:sid (await (initialize "owner-access")))]
            (is (= #{"whoami" "discover_actors" "resolve_address" "send_message"}
                   (set (list-names (await (run "owner-access" owner-sid (rpc "tools/list")))))))
            (is (= -32001 (get-in (await (run "owner-access" owner-sid (tool "read_inbox" {})))
                                 [:data "error" "code"]))))))
      (finally
        (when @app (await (.close (:app @app))))
        (fs/rmSync dir #js {:recursive true})))))

(deftest scoped-oauth-opens-only-its-authorized-mailbox-tools
  (async done
    (-> (verify-actor-messaging*)
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(defn- ^:async verify-node-auth* []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-mailbox-nodes-"))
        ledger (path/join dir "ledger.edn")
        root "did:web:axxium.test:actors:owner"
        grokbot (str root ":grokbot")
        work (str root ":work")
        group (str root ":group")
        other "did:web:axxium.test:actors:other:agent"
        policy {:policy/repos #{} :policy/permissions {} :policy/actions #{} :policy/forbid #{}}
        event-ids (fn [] {:decision-id (secrets/secure-id) :effect-id (secrets/secure-id) :at fixture/t0})
        grokbot-key (str "axb_" (secrets/secure-id))
        work-key (str "axb_" (secrets/secure-id))
        group-key (str "axb_" (secrets/secure-id))
        other-key (str "axb_" (secrets/secure-id))
        issue (fn [lg did key]
                (:ledger (gi/issue-bootstrap lg (state/fold lg) (gi/parent-of (state/fold lg) did) did
                                             (mcp/digest key) (event-ids))))
        tree (atom (-> (:ledger (gi/seed-ledger
                                 (gi/resolve-seed {:root {:id root :name "Owner" :policy policy}
                                                   :nodes [{:id grokbot :parent root :name "Grok Bot" :kind :bot :policy policy}
                                                           {:id work :parent root :name "Work" :kind :agent :policy policy}
                                                           {:id group :parent root :name "Group" :kind :group :policy policy}]}
                                                  "axxium.test")
                                 event-ids))
                       (#(:ledger (gi/register-root % (state/fold %) "did:web:axxium.test:actors:other" "Other" policy (event-ids))))
                       (#(:ledger (gi/provision-actor % (state/fold %) "did:web:axxium.test:actors:other" other "Other agent" policy :agent (event-ids))))
                       (issue grokbot grokbot-key)
                       (issue work work-key)
                       (issue group group-key)
                       (issue other other-key)))
        allowlist (atom nil)
        change-before-operation! (atom nil)
        app (atom nil)
        config cfg/get-in-config]
    (try
      (fs/writeFileSync ledger "" #js {:mode 384})
      (with-redefs [tc/read-key-file (constantly (js/Buffer.alloc 32))
                    identity/current-actor-state (fn [] (state/fold @tree))
                    identity/with-current-actor-state! (fn ^:async read-identity [f]
                                                        (when-let [change @change-before-operation!]
                                                          (reset! change-before-operation! nil)
                                                          (swap! tree change))
                                                        {:result (await (f (state/fold @tree)))})
                    cfg/get-in-config (fn [ks]
                                        (case (first ks)
                                          :mailbox/ledger-file ledger
                                          :mailbox/directory? true
                                          :mailbox/actor-bindings nil
                                          :mailbox/bootstrap-actor-dids @allowlist
                                          :github-identity/ledger-file (path/join dir "actors.edn")
                                          :identity/root-operator-binding {:root-did root :actor-id "actor_11111111-1111-4111-8111-111111111111"}
                                          :axxium/public-base-url origin
                                          (config ks)))]
        (reset! app (await (start-app!)))
        (let [as (fn [did key] {"x-axxium-actor" did "authorization" (str "Bearer " key)})
              run (fn [creds sid body] (request! @app "POST" nil sid body creds))
              open! (fn [creds] (.then (run creds nil (rpc "initialize")) (fn [r] (:sid r))))
              g (as grokbot grokbot-key)
              w (as work work-key)]
          (is (= 401 (:status (await (run g nil (rpc "initialize"))))) "Long-lived mailbox bootstrap is off by default.")
          (reset! allowlist #{grokbot group other})
          (is (= 401 (:status (await (run w nil (rpc "initialize"))))) "An unlisted sibling is refused.")
          (is (= 401 (:status (await (run (as other other-key) nil (rpc "initialize")))))
              "A listed actor outside the configured root is refused.")
          (is (= 401 (:status (await (run (as group group-key) nil (rpc "initialize")))))
              "A listed group is refused.")
          (reset! allowlist #{grokbot work})
          (let [g-sid (await (open! g))
                w-sid (await (open! w))]
            (is (= 401 (:status (await (run (as grokbot work-key) nil (rpc "initialize"))))) "A node's key is its own.")
            (is (string? g-sid) "Grok Bot authenticates as its own node.")
            (await (run g g-sid (tool "register_mailbox" {})))
            (await (run w w-sid (tool "register_mailbox" {})))
            (is (= grokbot (get (result (await (run g g-sid (tool "whoami" {})))) "did")))
            (is (= #{work}
                   (set (map #(get % "did") (get (result (await (run g g-sid (tool "discover_actors" {})))) "actors")))))
            (is (= "accepted" (get (result (await (run g g-sid (tool "send_message" {"to" work "idempotency_key" "xh-1"
                                                                                    "body" {"text" "hello work"}}))))
                                   "accepted")))
            (let [inbox (result (await (run w w-sid (tool "read_inbox" {}))))]
              (is (= grokbot (get-in inbox ["messages" 0 "message_from"])))
              (is (= "hello work" (get-in inbox ["messages" 0 "message_body" "text"]))))
            (let [before (fs/readFileSync ledger "utf8")]
              (reset! change-before-operation!
                      #(:ledger (gi/bind-mailbox % (state/fold %) root fixture/alice grokbot (event-ids))))
              (is (= 401 (:status (await (run g g-sid (tool "read_inbox" {})))))
                  "Binding committed after preliminary authentication blocks the queued read.")
              (is (= before (fs/readFileSync ledger "utf8"))))
            (is (= grokbot (get (gi/mailbox-bindings (state/fold @tree)) fixture/alice)))
            (is (= 401 (:status (await (run g nil (rpc "initialize")))))
                "A bootstrap key cannot authenticate at a bound external mailbox.")
            (swap! tree #(:ledger (gi/suspend % (state/fold %) root work (event-ids))))
            (is (= 401 (:status (await (run w w-sid (tool "whoami" {})))))
                "A suspended node loses mailbox access."))))
      (finally
        (when @app (await (.close (:app @app))))
        (fs/rmSync dir #js {:recursive true})))))

(deftest agent-nodes-message-as-themselves
  (async done
    (-> (verify-node-auth*)
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
