(ns axxium.routes.mailbox
  "Opt-in authenticated polling and selected-actor webhook transport."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.domain.mailbox-mcp :as mailbox]
            [axxium.extern.agent-credentials :as secrets]
            [axxium.extern.http :as http]
            [axxium.extern.ledger-file :as disk]
            [axxium.extern.ledger-queue :as queue]
            [axxium.extern.mailbox-mcp :as rpc]
            [axxium.law.actor :as actor-law]
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

(defn- accept-json? [req]
  (let [accept (or (http/header req "accept") "")]
    (some #{"application/json"}
          (map #(first (str/split (str/trim %) #";" 2)) (str/split accept #",")))))

(defn- rpc-error [reply status id code message]
  (http/send-secret! reply status
                     {"jsonrpc" "2.0" "id" id "error" {"code" code "message" message}}))

(defn- operation [lg ctx body]
  (if (= "initialize" (get body "method"))
    (let [opened (mailbox/call lg ctx :open_session
                              {:audience (:audience ctx) :ttl-ms 3600000})]
      (assoc (rpc/handle-rpc (:ledger opened) ctx body)
             :transport-session (get-in opened [:result :session/id])))
    (if-let [error (mailbox/session-error lg ctx)]
      {:session-error error}
      (case (get body "method")
        ("notifications/initialized" "notifications/cancelled") {:ledger lg}
        "ping" {:ledger lg :response {"jsonrpc" "2.0" "id" (get body "id") "result" {}}}
        (rpc/handle-rpc lg (assoc ctx :session/current-only? true) body)))))

(defn- ^:async post-mailbox!
  [writer path resource runtime oauth resource-metadata req reply {:keys [principal cred-id] :as proof}]
  (let [body (http/json-body req)
        version (http/header req "mcp-protocol-version")
        sid (http/header req "mcp-session-id")
        initialize? (= "initialize" (get body "method"))]
    (cond
      (not (accept-json? req))
      (http/send-secret! reply 406 {:error "Mailbox polling requires application/json"})

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
      (let [ctx {:principal principal
                 :session/id sid
                 :session/binding (secrets/token-hash (if-let [grant (:oauth/grant-id proof)]
                                                       (pr-str ["oauth" (:oauth/client-id proof) resource grant])
                                                       cred-id))
                 :session/new-id (when initialize? (secrets/secure-id))
                 :session/push-supported? false
                 :events/enabled? (events/enabled? runtime proof)
                 :events/only? (some? (:oauth/grant-id proof))
                 :audience resource}
            out (if (and (:events/enabled? ctx) (contains? events/event-methods (get body "method")))
                  (await (events/execute! runtime proof ctx body))
                  (await (queue/submit! writer path
                           (fn ^:async apply-mailbox [lg]
                             (if (await (active-proof! oauth proof))
                               (operation lg (assoc ctx :now (secrets/now)) body)
                               {:unauthenticated? true})))))]
        (cond
          (:unauthenticated? out) (require-auth! reply resource-metadata)

          (:session-error out)
          (rpc-error reply 404 (get body "id") -32600 "Mailbox session not found")

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
  (when-let [path (cfg/get-in-config [:mailbox/ledger-file])]
    (let [origin (http/origin-settings (cfg/get-in-config [:axxium/public-base-url]) endpoint)
          resource-metadata (when oauth (str (:origin origin) resource-metadata-path))
          owner (cfg/get-in-config [:mailbox/webhook-actor-did])
          allowed-dids (cfg/get-in-config [:mailbox/pilot-allowed-dids])
          webhook-key-file (cfg/get-in-config [:mailbox/webhook-key-file])
          storage (disk/private-ledger-storage path [(cfg/get-in-config [:drive/ledger-file])
                                                     (cfg/get-in-config [:github-identity/ledger-file])]
                                              (some? owner))
          writer (queue/create storage)
          runtime (events/install! app writer path owner webhook-key-file (partial active-proof! oauth))
          no-stream (authorized origin oauth resource-metadata allowed-dids
                                (fn [_ reply _]
                                  (http/set-header! reply "Allow" "POST")
                                  (http/send-empty! reply 405)))]
      (when oauth
        (http/get! app resource-metadata-path
                   (fn [_ reply]
                     (http/send-secret! reply 200 {"resource" (:resource oauth)
                                                    "authorization_servers" [(:issuer oauth)]
                                                    "scopes_supported" [(:scope oauth)]
                                                    "bearer_methods_supported" ["header"]}))))
      (http/post! app endpoint
                  (authorized origin oauth resource-metadata allowed-dids
                              (partial post-mailbox! writer path (:resource origin) runtime oauth resource-metadata)))
      (http/get! app endpoint no-stream)
      (http/delete! app endpoint no-stream)))))
