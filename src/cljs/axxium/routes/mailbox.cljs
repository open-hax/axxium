(ns axxium.routes.mailbox
  "Opt-in authenticated polling and selected-actor webhook transport."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.domain.github-identity :as tree]
            [axxium.domain.mailbox-events :as access]
            [axxium.domain.mailbox-mcp :as mailbox]
            [axxium.extern.agent-credentials :as secrets]
            [axxium.extern.http :as http]
            [axxium.extern.ledger-file :as disk]
            [axxium.extern.ledger-queue :as queue]
            [axxium.extern.mailbox-mcp :as rpc]
            [axxium.extern.mailbox-stream :as stream]
            [axxium.law.actor :as actor-law]
            [axxium.routes.github-identity :as identity]
            [axxium.routes.mailbox-events :as events]
            [clojure.string :as str]))

(def ^:private endpoint "/api/mailbox/mcp")
(def ^:private resource-metadata-path "/.well-known/oauth-protected-resource/api/mailbox/mcp")

(defn- admitted-origin? [req {:keys [host origin]}]
  (let [incoming (http/header req "origin")]
    (and (= host (some-> (http/header req "host") str/lower-case))
         (or (nil? incoming) (= origin incoming)))))

(defn- request-token [req origin]
  (if-let [authorization (http/authorization req)]
    (second (re-matches #"(?i)Bearer ([^\s]+)" authorization))
    (when (= origin (http/header req "origin"))
      (http/cookie req session/COOKIE-NAME))))

(defn- require-auth! [reply resource-metadata]
  (http/set-header! reply "WWW-Authenticate"
                    (if resource-metadata (str "Bearer resource_metadata=\"" resource-metadata "\"") "Bearer"))
  (http/send-secret! reply 401 {:error "A live AT Protocol session is required"}))

(defn- active-proof! [oauth proof]
  (if (:oauth/grant-id proof)
    ((:active-grant? oauth) proof)
    (session/active-atproto-session? proof)))

(defn- authorized
  [origin oauth resource-metadata allowed-dids handler]
  (fn ^:async mailbox-handler [req reply]
    (try
      (if-not (admitted-origin? req origin)
        (http/send-secret! reply 403 {:error "Mailbox origin refused"})
        (let [token (request-token req (:origin origin))
              proof (or (await (session/atproto-session-proof token))
                        (when (and oauth token (http/authorization req))
                          (await ((:access-proof! oauth) token))))]
          (cond
            (not (and proof (await (active-proof! oauth proof))))
            (require-auth! reply resource-metadata)

            (and allowed-dids (not (contains? allowed-dids (get-in proof [:principal :auth/did]))))
            (http/send-secret! reply 403 {:error "Mailbox access refused"})

            :else (await (handler req reply proof)))))
      (catch :default _
        (http/send-secret! reply 503 {:error "Mailbox is unavailable"})))))

(defn- rpc-request? [body]
  (and (map? body)
       (= "2.0" (get body "jsonrpc"))
       (string? (get body "method"))
       (or (not (contains? body "params")) (map? (get body "params")))
       (or (not (contains? body "id"))
           (let [id (get body "id")] (or (string? id) (int? id))))))

(defn- supported-message? [body]
  (let [method (get body "method")
        params (get body "params")]
    (cond
      (contains? #{"notifications/initialized" "notifications/cancelled"} method)
      (not (contains? body "id"))

      (not (contains? body "id")) false

      (= "tools/call" method)
      (and (string? (get params "name"))
           (or (not (contains? params "arguments")) (map? (get params "arguments"))))

      :else true)))

(defn- accepts? [req content-type]
  (let [accept (or (http/header req "accept") "")]
    (some #{content-type}
          (map #(first (str/split (str/trim %) #";" 2)) (str/split accept #",")))))

(defn- transport-context [resource proof sid]
  (let [{:keys [principal cred-id]} proof]
    {:principal principal
     :session/id sid
     :session/binding (secrets/token-hash (if-let [grant (:oauth/grant-id proof)]
                                          (pr-str ["oauth" (:oauth/client-id proof) resource grant])
                                          cred-id))
     :audience resource}))

(defn- stream-eligible? [stream-runtime proof]
  (and stream-runtime (nil? (:oauth/grant-id proof))))

(defn- ^:async active-stream-session?
  [writer path oauth proof ctx bindings]
  (let [address (get-in proof [:principal :auth/did])
        actor (get bindings address address)
        root (:root-did (cfg/get-in-config [:identity/root-operator-binding]))]
    (and (await (active-proof! oauth proof))
         (or (contains? bindings address)
             (= :actor-bootstrap (get-in proof [:principal :auth/method])))
         (tree/active-owner-delegation? (identity/current-actor-state) root actor)
         (not (mailbox/session-error (queue/current-ledger writer path)
                                     (assoc ctx :now (secrets/now)))))))

(defn- ^:async listen-inbox!
  [writer path oauth resource bindings stream-runtime req reply proof]
  (let [sid (http/header req "mcp-session-id")
        version (http/header req "mcp-protocol-version")
        ctx (transport-context resource proof sid)
        recheck #(active-stream-session? writer path oauth proof ctx bindings)]
    (cond
      (not (stream-eligible? stream-runtime proof))
      (do (http/set-header! reply "Allow" "POST")
          (http/send-empty! reply 405))
      (not (accepts? req "text/event-stream"))
      (http/send-secret! reply 406 {:error "Inbox notifications require text/event-stream"})
      (and version (not= version rpc/protocol-version))
      (http/send-secret! reply 400 {:error "Unsupported MCP protocol version"})
      (nil? sid)
      (http/send-secret! reply 400 {:error "Mcp-Session-Id is required"})
      (not (and (actor-law/valid-credential-id? sid) (await (recheck))))
      (http/send-secret! reply 404 {:error "Mailbox session not found"})
      :else (stream/listen! stream-runtime req reply sid (get-in proof [:principal :auth/did]) recheck))))

(defn- ^:async notify-inbox!
  [writer path stream-runtime recipient]
  (doseq [{:keys [authorize! write! close!]} (stream/subscriptions stream-runtime recipient)]
    (try
      (if (and (await (authorize!))
               (access/authorized? (queue/current-ledger writer path) recipient (secrets/now)))
        (write!)
        (close!))
      (catch :default _
        (close!)))))

(defn- rpc-error [reply status id code message]
  (http/send-secret! reply status
                     {"jsonrpc" "2.0" "id" id "error" {"code" code "message" message}}))

(defn- operation [lg ctx body]
  (if (= "initialize" (get body "method"))
    (let [opened (mailbox/call lg ctx :open_session
                              {:audience (:audience ctx) :ttl-ms 3600000
                               :push (:resources/enabled? ctx)})]
      (assoc (rpc/handle-rpc (:ledger opened) ctx body)
             :transport-session (get-in opened [:result :session/id])))
    (if-let [error (mailbox/session-error lg ctx)]
      {:session-error error}
      (case (get body "method")
        ("notifications/initialized" "notifications/cancelled") {:ledger lg}
        "ping" {:ledger lg :response {"jsonrpc" "2.0" "id" (get body "id") "result" {}}}
        (rpc/handle-rpc lg (assoc ctx :session/current-only? true) body)))))

(defn- ^:async submit-operation!
  [writer path oauth proof ctx body bindings stream-runtime]
  (let [submit (fn ^:async commit-mailbox [ctx]
                 (let [out (await
                            (queue/submit! writer path
                                           (fn ^:async apply-mailbox [lg]
                                             (if (await (active-proof! oauth proof))
                                               (operation lg (assoc ctx :now (secrets/now)) body)
                                               {:unauthenticated? true}))))]
                   (when (and stream-runtime (:notify-to out))
                     (await (notify-inbox! writer path stream-runtime (:notify-to out))))
                   out))]
    (if (and bindings (or (nil? (:oauth/grant-id proof))
                          (:mailbox/actors? proof) (:mailbox/owner-root-did proof)))
      ;; Identity is acquired before mailbox. Keep it through the durable append
      ;; so a completed actor suspension cannot race an authorized send.
      (:result (await (identity/with-current-actor-state!
                       (fn [st] (submit (assoc ctx :directory/state st :directory/bindings bindings))))))
      (await (submit ctx)))))

(defn- ^:async post-mailbox!
  [writer path resource runtime subscriptions-disabled? oauth bindings resource-metadata stream-runtime
   req reply {:keys [principal] :as proof}]
  (let [body (http/json-body req)
        version (http/header req "mcp-protocol-version")
        sid (http/header req "mcp-session-id")
        initialize? (= "initialize" (get body "method"))]
    (cond
      (not (accepts? req "application/json"))
      (http/send-secret! reply 406 {:error "Mailbox requests require application/json"})

      (not (and (rpc-request? body) (supported-message? body)))
      (rpc-error reply 400 nil -32600 "Invalid request")

      (and (some? version) (not= version rpc/protocol-version))
      (rpc-error reply 400 (get body "id") -32600 "Unsupported MCP protocol version")

      (and initialize? (some? sid))
      (rpc-error reply 400 (get body "id") -32600 "Initialize without Mcp-Session-Id")

      (and (not initialize?) (nil? sid))
      (rpc-error reply 400 (get body "id") -32600 "Mcp-Session-Id is required")

      (and (not initialize?) (not (actor-law/valid-credential-id? sid)))
      (rpc-error reply 404 (get body "id") -32600 "Mailbox session not found")

      :else
      (let [streaming? (boolean (stream-eligible? stream-runtime proof))
            ctx (assoc (transport-context resource proof sid)
                 :session/new-id (when initialize? (secrets/secure-id))
                 :session/push-supported? streaming?
                 :resources/enabled? streaming?
                 :events/enabled? (events/enabled? runtime proof)
                 :events/subscriptions-disabled? subscriptions-disabled?
                 :events/only? (some? (:oauth/grant-id proof))
                 :mailbox/actors? (:mailbox/actors? proof))
            out (if (and (:events/enabled? ctx) (contains? events/event-methods (get body "method")))
                  (await (events/execute! runtime (dissoc proof :mailbox/actors?) ctx body))
                  (await (submit-operation! writer path oauth proof ctx body bindings stream-runtime)))
            applied? (case (:inbox-subscription out)
                       :subscribe (stream/subscribe! stream-runtime sid (:auth/did principal))
                       :unsubscribe (stream/unsubscribe! stream-runtime sid (:auth/did principal))
                       true)]
        (cond
          (:unauthenticated? out) (require-auth! reply resource-metadata)

          (:session-error out)
          (rpc-error reply 404 (get body "id") -32600 "Mailbox session not found")

          (not applied?)
          (rpc-error reply 503 (get body "id") -32000 "Inbox notification subscriptions are unavailable")

          (:response out)
          (do (when-let [session-id (:transport-session out)]
                (http/set-header! reply "Mcp-Session-Id" session-id))
              (http/send-secret! reply 200 (:response out)))

          :else
          (http/send-empty! reply 202))))))

(defn register-mailbox-routes!
  "Mount only when a dedicated mailbox ledger is explicitly configured."
  ([app] (register-mailbox-routes! app nil))
  ([app oauth]
   (let [path (cfg/get-in-config [:mailbox/ledger-file])
         identity-path (cfg/get-in-config [:github-identity/ledger-file])
         bindings (cfg/get-in-config [:mailbox/actor-bindings])
         allowed-dids (cfg/get-in-config [:mailbox/pilot-allowed-dids])
         streaming? (cfg/get-in-config [:mailbox/stream-enabled?])
         subscriptions-disabled? (false? (cfg/get-in-config [:mailbox/subscriptions-enabled?]))]
     (when (or (and bindings (or (not path) allowed-dids (str/blank? identity-path)))
               (and (:actor-messaging? oauth) (not bindings)))
       (throw (ex-info "Mailbox actor messaging requires both ledgers and explicit bindings, outside the two-actor pilot"
                       {:reason :invalid-mailbox-actor-messaging-config})))
     (when (and streaming? (not (and bindings subscriptions-disabled?
                                      (cfg/get-in-config [:identity/root-operator-binding]))))
       (throw (ex-info "Mailbox notifications require the owner directory with webhook subscriptions disabled"
                       {:reason :invalid-mailbox-stream-config})))
     (when path
       (let [origin (http/origin-settings (cfg/get-in-config [:axxium/public-base-url]) endpoint)
             resource-metadata (when oauth (str (:origin origin) resource-metadata-path))
             owner (cfg/get-in-config [:mailbox/webhook-actor-did])
             webhook-key-file (cfg/get-in-config [:mailbox/webhook-key-file])
             storage (disk/private-ledger-storage path [(cfg/get-in-config [:drive/ledger-file]) identity-path]
                                                  (some? owner))
             writer (queue/create storage)
             stream-runtime (when streaming? (stream/create! app))
             runtime (when-not subscriptions-disabled?
                       (events/install! app writer path owner webhook-key-file (partial active-proof! oauth)))
             no-stream (authorized origin oauth resource-metadata allowed-dids
                                   (fn [_ reply _]
                                     (http/set-header! reply "Allow" "POST")
                                     (http/send-empty! reply 405)))]
         (when oauth
           (http/get! app resource-metadata-path
                      (fn [_ reply]
                        (http/send-secret! reply 200 {"resource" (:resource oauth)
                                                       "authorization_servers" [(:issuer oauth)]
                                                       "scopes_supported" (or (:scopes oauth) [(:scope oauth)])
                                                       "bearer_methods_supported" ["header"]}))))
         (http/post! app endpoint
                     (authorized origin oauth resource-metadata allowed-dids
                                 (partial post-mailbox! writer path (:resource origin) runtime subscriptions-disabled? oauth bindings
                                          resource-metadata stream-runtime)))
         (http/get! app endpoint
                    (if stream-runtime
                      (authorized origin oauth resource-metadata allowed-dids
                                  (partial listen-inbox! writer path oauth (:resource origin) bindings stream-runtime))
                      no-stream))
         (http/delete! app endpoint no-stream))))))
