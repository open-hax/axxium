(ns axxium.extern.mailbox-stream-http-test
  "Exercise same-endpoint MCP resource notifications over real loopback SSE."
  (:require [axxium.auth.token :as token]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.domain.github-identity :as gi]
            [axxium.domain.kernel-fixture :as fixture]
            [axxium.domain.state :as state]
            [axxium.extern.agent-credentials :as secrets]
            [axxium.extern.fastify :as fastify]
            [axxium.extern.github-identity-mcp :as bootstrap]
            [axxium.extern.signing-key :as signing]
            [axxium.routes.github-identity :as identity]
            [axxium.routes.mailbox :as routes]
            [clojure.string :as str]
            [cljs.test :refer [async deftest is]]
            ["@fastify/cookie" :default fastify-cookie]
            ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:stream" :as stream]))

(def ^:private origin "http://axxium.test")
(def ^:private endpoint "/api/mailbox/mcp")
(def ^:private inbox "axxium://mailbox/inbox")
(def ^:private protocol "2025-06-18")
(def ^:private codex-at fixture/bob)

(defn- rpc [method params]
  (cond-> {"jsonrpc" "2.0" "id" 1 "method" method}
    params (assoc "params" params)))

(defn- tool [name arguments]
  (rpc "tools/call" {"name" name "arguments" arguments}))

(defn- resource-request [method uri]
  (rpc method {"uri" uri}))

(defn- send [to idempotency-key body]
  (tool "send_message" {"to" to "idempotency_key" idempotency-key "body" body}))

(defn- request! [{:keys [url bearer did sid signal]} method message]
  (let [headers (cond-> {"host" "axxium.test"
                         "accept" "application/json, text/event-stream"
                         "authorization" (str "Bearer " bearer)}
                  message (assoc "content-type" "application/json")
                  did (assoc "x-axxium-actor" did)
                  sid (assoc "mcp-session-id" sid "mcp-protocol-version" protocol))]
    (js/Promise.
     (fn [resolve reject]
       (let [req (http/request
                  url #js {:method method :headers (clj->js headers) :signal signal}
                  (fn [response]
                    (resolve {:status (.-statusCode response)
                              :sid (aget (.-headers response) "mcp-session-id")
                              :content-type (aget (.-headers response) "content-type")
                              :body (stream/Readable.toWeb response)})))]
         (.once req "error" reject)
         (.end req (when message (js/JSON.stringify (clj->js message)))))))))

(defn- ^:async post! [client message]
  (let [{:keys [body] :as response} (await (request! client "POST" message))
        text (await (.text (js/Response. body)))]
    (assoc (dissoc response :body) :json (when (seq text) (js->clj (js/JSON.parse text))))))

(defn- result [response]
  (get-in response [:json "result"]))

(defn- structured [response]
  (get (result response) "structuredContent"))

(defn- ^:async initialize! [client]
  (let [{:keys [status sid] :as response} (await (post! client (rpc "initialize" nil)))]
    (is (= 200 status))
    (is (= true (get-in (result response) ["capabilities" "resources" "subscribe"])))
    (is (not (contains? (get (result response) "capabilities") "events")))
    (when-not (string? sid)
      (throw (ex-info "MCP initialization returned no session ID" {:status status})))
    (assoc client :sid sid)))

(defn- ^:async next-frame! [{:keys [buffer decoder reader] :as source}]
  (if-let [separator (re-find #"\r?\n\r?\n" @buffer)]
    (let [index (str/index-of @buffer separator)
          frame (subs @buffer 0 index)]
      (swap! buffer subs (+ index (count separator)))
      frame)
    (let [chunk (await (.read reader))]
      (when (.-done chunk)
        (throw (ex-info "Mailbox SSE ended before a complete frame" {})))
      (swap! buffer str (.decode decoder (.-value chunk) #js {:stream true}))
      (await (next-frame! source)))))

(defn- ^:async connect! [client]
  (let [{:keys [status content-type body]} (await (request! client "GET" nil))]
    (is (= 200 status))
    (is (str/starts-with? (or content-type "") "text/event-stream"))
    (let [source {:buffer (atom "") :decoder (js/TextDecoder.) :reader (.getReader body)}]
      (is (= ": connected" (await (next-frame! source))))
      source)))

(defn- ^:async next-update! [source]
  (let [frame (await (next-frame! source))
        lines (str/split-lines frame)
        data (keep #(when (str/starts-with? % "data:") (str/triml (subs % 5))) lines)]
    (if (seq data)
      {:event (some #(when (str/starts-with? % "event:") (str/triml (subs % 6))) lines)
       :message (js->clj (js/JSON.parse (str/join "\n" data)))}
      (await (next-update! source)))))

(defn- ^:async expect-message! [client source sender body receipt]
  (is (= "accepted" (get receipt "accepted")))
  (is (= {:event "message" :message {"jsonrpc" "2.0" "method" "notifications/resources/updated"
                                     "params" {"uri" inbox}}}
         (await (next-update! source))))
  ;; Read only after the native notification. A transport-level wakeup is not
  ;; a delivery receipt until the actor's authenticated read sees the message.
  (let [response (await (post! client (resource-request "resources/read" inbox)))
        entry (get-in (result response) ["contents" 0])
        messages (get (js->clj (js/JSON.parse (get entry "text"))) "messages")
        received (first messages)]
    (is (= 200 (:status response)))
    (is (= [inbox "application/json"] [(get entry "uri") (get entry "mimeType")]))
    (is (= 1 (count messages)))
    (is (string? (get receipt "message_id")))
    (is (= (get receipt "message_id") (get received "message_id")))
    (is (= sender (get received "message_from")))
    (is (= body (get received "message_body")))))

(defn- ^:async expect-no-extra-update! [source]
  (let [timer (atom nil)
        quiet (js/Promise. (fn [resolve]
                             (reset! timer (js/setTimeout #(resolve ::quiet) 250))))]
    (try
      (is (= ::quiet (await (js/Promise.race #js [(next-update! source) quiet])))
          "Each mailbox received only its own newly accepted message.")
      (finally (js/clearTimeout @timer)))))

(defn- ^:async denied-stream-status! [client]
  (let [{:keys [status body]} (await (request! client "GET" nil))]
    (await (.cancel body))
    status))

(defn- fixture-state [root grok codex grok-key]
  (let [policy {:policy/repos #{} :policy/permissions {} :policy/actions #{} :policy/forbid #{}}
        ids (fn [] {:decision-id (secrets/secure-id) :effect-id (secrets/secure-id) :at fixture/t0})
        seed (gi/resolve-seed {:root {:id root :name "Owner" :policy policy}
                              :nodes [{:id grok :parent root :name "Grok Bot" :kind :bot :policy policy}
                                      {:id codex :parent root :name "Codex" :kind :agent :policy policy}]}
                             "axxium.test")
        ledger (:ledger (gi/seed-ledger seed ids))
        bound (:ledger (gi/seed-bindings ledger {codex-at codex} ids))]
    (state/fold (:ledger (gi/issue-bootstrap bound (state/fold bound) root grok
                                           (bootstrap/digest grok-key) (ids))))))

(defn- ^:async roundtrip! [base grok-key grok codex-token]
  (let [grok-client (await (initialize! (assoc base :bearer grok-key :did grok)))
        codex-client (await (initialize! (assoc base :bearer codex-token)))
        register (tool "register_mailbox" {})
        subscribe (resource-request "resources/subscribe" inbox)]
    (is (= (str "mbox/" grok) (get (structured (await (post! grok-client register))) "mailbox")))
    (is (= (str "mbox/" codex-at) (get (structured (await (post! codex-client register))) "mailbox")))
    (is (= {} (result (await (post! grok-client subscribe)))))
    (is (= {} (result (await (post! codex-client subscribe)))))
    (is (= 401 (await (denied-stream-status! (assoc grok-client :bearer "axb_wrong-fixture-key")))))
    (is (= 404 (await (denied-stream-status! (assoc codex-client :sid (:sid grok-client))))))
    (is (= 404 (await (denied-stream-status! (assoc grok-client :sid (:sid codex-client))))))
    (let [other-uri (str inbox "/" codex-at)
          other-stream (await (post! grok-client (resource-request "resources/subscribe" other-uri)))]
      (is (= -32602 (get-in other-stream [:json "error" "code"]))))
    (let [grok-stream (await (connect! grok-client))
          codex-stream (await (connect! codex-client))
          from-grok {"text" "Synthetic Grok to Codex"}
          from-codex {"text" "Synthetic Codex to Grok"}
          grok-send (send codex-at "grok-fixture" from-grok)
          grok-receipt (structured (await (post! grok-client grok-send)))]
      (await (expect-message! codex-client codex-stream grok from-grok grok-receipt))
      (let [codex-receipt (structured (await (post! codex-client (send grok "codex-fixture" from-codex))))]
        (is (not= (get grok-receipt "message_id") (get codex-receipt "message_id")))
        (await (expect-message! grok-client grok-stream codex-at from-codex codex-receipt)))
      (is (= grok-receipt (structured (await (post! grok-client grok-send)))))
      (await (js/Promise.all #js [(expect-no-extra-update! grok-stream)
                                 (expect-no-extra-update! codex-stream)])))))

(defn- ^:async verify-native-stream! []
  (let [keys (await (signing/generate-ephemeral!))
        dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-mailbox-stream-"))
        ledger (path/join dir "mailbox.edn")
        identity-key (path/join dir "identity-key")
        root "did:web:axxium.test:actors:owner"
        grok (str root ":grokbot")
        codex (str root ":codex")
        grok-key (str "axb_" (.toString (js/Buffer.alloc 32 1) "base64url"))
        actor-id "actor_22222222-2222-4222-8222-222222222222"
        cred-id (secrets/secure-id)
        actor-state (fixture-state root grok codex grok-key)
        controller (js/AbortController.)
        deadline (js/setTimeout #(.abort controller) 12000)
        app (fastify/create-app)
        config cfg/get-in-config]
    (try
      (fs/writeFileSync ledger "" #js {:mode 384})
      (fs/writeFileSync identity-key (.toString (js/Buffer.alloc 32 6) "base64") #js {:mode 384})
      (with-redefs [signing/current! (constantly keys)]
        (let [codex-token (await (token/create-atproto-token! {:id actor-id} cred-id codex-at))
              query (db/q-select-actor-by-atproto-session actor-id (secrets/token-hash codex-token)
                                                         cred-id codex-at)]
          (with-redefs [db/query-auth-proof! (fn [q] (js/Promise.resolve (when (= q query) {:id actor-id})))
                        identity/current-actor-state (constantly actor-state)
                        identity/with-current-actor-state! (fn ^:async with-fixture-state [f]
                                                            {:result (await (f actor-state))})
                        cfg/get-in-config (fn [ks]
                                            (case (first ks)
                                              :mailbox/ledger-file ledger
                                              :mailbox/actor-bindings {codex-at codex}
                                              :mailbox/bootstrap-actor-dids #{grok}
                                              :mailbox/stream-enabled? true
                                              :mailbox/subscriptions-enabled? false
                                              :github-identity/ledger-file (path/join dir "actors.edn")
                                              :github-identity/token-key-file identity-key
                                              :identity/root-operator-binding
                                              {:root-did root :actor-id "actor_11111111-1111-4111-8111-111111111111"}
                                              :axxium/public-base-url origin
                                              (config ks)))]
            (.register app fastify-cookie)
            (routes/register-mailbox-routes! app)
            (let [url (str (await (.listen app #js {:port 0 :host "127.0.0.1"})) endpoint)]
              (await (roundtrip! {:url url :signal (.-signal controller)} grok-key grok codex-token))))))
      (finally
        (js/clearTimeout deadline)
        (.abort controller)
        (await (.close app))
        (fs/rmSync dir #js {:recursive true :force true})))))

(deftest bootstrap-and-bound-at-actors-deliver-over-native-sse
  (async done
    (-> (verify-native-stream!)
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
