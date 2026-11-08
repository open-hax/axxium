(ns axxium.extern.mailbox-mcp
  "JSON-RPC wire adapter for the addressable mailbox."
  (:require [axxium.domain.mailbox-mcp :as mailbox]
            [axxium.law.principal :as principal]
            [clojure.string :as str]))

(def protocol-version "2025-06-18")

(def ^:private str-schema {"type" "string"})

(defn- tool [name desc props required annotations]
  {"name" name "description" desc
   "inputSchema" {"type" "object" "properties" props "required" required
                  "additionalProperties" false}
   "annotations" (merge {"readOnlyHint" false "destructiveHint" false
                          "idempotentHint" false "openWorldHint" false}
                         annotations)})

(def ^:private read-hints
  {"readOnlyHint" true "idempotentHint" true})

(def tool-descriptors
  [(tool "whoami" "The authenticated actor and session." {} [] read-hints)
   (tool "open_session" "Bind a session to this actor and audience; push only if the client declares it."
         {"audience" str-schema "ttl_ms" {"type" "integer"} "push" {"type" "boolean"}} ["audience" "ttl_ms"] {})
   (tool "resolve_address" "Resolve a DID or mbox/<did> to a mailbox." {"address" str-schema} ["address"] read-hints)
   (tool "send_message" "Send to a mailbox. Repeating an idempotency key with the same body returns the original receipt."
         {"to" str-schema "from" str-schema "idempotency_key" str-schema "body" {"type" "object"}}
         ["to" "idempotency_key" "body"] {"idempotentHint" true "openWorldHint" true})
   (tool "read_inbox" "Read inbox messages. A supplied cursor cannot skip an unacknowledged message."
         {"mailbox" str-schema "after" {"type" "integer"}} [] read-hints)
   (tool "acknowledge_message" "Acknowledge one message; return the contiguous durable cursor."
         {"message_id" str-schema} ["message_id"] {})
   (tool "get_delivery" "Acceptance, notification and acknowledgement state. Never claims execution."
         {"message_id" str-schema} ["message_id"] read-hints)
   (tool "register_mailbox" "Register your mailbox and its owner grant." {} [] {})
   (tool "grant_send" "Change authorization: allow an actor to send to your mailbox."
         {"to" str-schema} ["to"] {"destructiveHint" true})
   (tool "revoke_send" "Change authorization: revoke an actor's send grant on your mailbox."
         {"to" str-schema} ["to"] {"destructiveHint" true})])

(defn- arg-key [k] (keyword (str/replace k "_" "-")))

(defn- json-key [k]
  (let [n (str/replace (name k) #"[?]$" "")
        n (str/replace n "-" "_")]
    (if (= "message" (namespace k)) (str "message_" n) n)))

(def ^:private opaque-keys
  "Envelope keys whose values are user payload: carried verbatim, never
   renamed. A JSON body arrives string-keyed and is stored exactly as
   received, so the ledger's canonical EDN holds the lossless value."
  #{:message/body :body})

(defn- payload->json
  "Payload to JSON without collapsing keys: keyword keys keep their full
   qualified name (:a/id -> \"a/id\"), string keys pass through."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(if (keyword? k) (subs (str k) 1) (str k)) (payload->json v)])) x)
    (coll? x) (mapv payload->json x)
    (keyword? x) (subs (str x) 1)
    (inst? x) (.toISOString x)
    :else x))

(defn- ->json [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(if (keyword? k) (json-key k) (str k))
                                        (if (opaque-keys k) (payload->json v) (->json v))])) x)
    (coll? x) (mapv ->json x)
    (keyword? x) (name x)
    (inst? x) (.toISOString x)
    :else x))

(defn- rpc-error [id code message]
  {"jsonrpc" "2.0" "id" id "error" {"code" code "message" message}})

(def ^:private error-actions
  {:unauthenticated "Sign in before calling this tool."
   :no-session "Open a session before calling this tool."
   :session-mismatch "Use the session bound to the authenticated actor."
   :wrong-audience "Open a session for this application's audience."
   :session-expired "Open a new session and retry."
   :unknown-tool "Use tools/list to select an available tool."
   :forbidden "Request the mailbox owner's permission before retrying."
   :not-found "Check the resource id and use its authorized owner."
   :idempotency-conflict "Use a new idempotency key for changed content."
   :bad-idempotency-key "Use a 1–256 character key with valid Unicode."
   :bad-body "Send an object with valid Unicode, finite numbers, and safe integers."
   :secret-in-payload "Remove credentials from the message body."
   :forged-sender "Omit from or use the authenticated actor's DID."})

(defn- tool-error-message [error]
  (str (name error) ": " (get error-actions error "Check the tool arguments and retry with an authorized session.")))

(defn- rpc-request
  "Evaluate a request; the public adapter suppresses notification responses."
  [lg ctx {:strs [id method params]}]
  (cond
    (not (principal/authenticated? (:principal ctx)))
    {:ledger lg :response (rpc-error id -32001 "unauthenticated")}

    (= method "initialize")
    {:ledger lg :response {"jsonrpc" "2.0" "id" id
                           "result" {"protocolVersion" protocol-version
                                     "serverInfo" {"name" "axxium-mailbox" "version" "0.1.0"}
                                     "capabilities" {"tools" {}}}}}

    (= method "tools/list")
    {:ledger lg :response {"jsonrpc" "2.0" "id" id "result" {"tools" tool-descriptors}}}

    (= method "tools/call")
    (let [tool-name (get params "name")
          args (into {} (map (fn [[k v]] [(arg-key k) v]))
                     (get params "arguments"))
          {:keys [ledger result error]} (mailbox/call lg ctx (keyword tool-name) args)]
      {:ledger ledger
       :response {"jsonrpc" "2.0" "id" id
                  "result" (if error
                             {"isError" true "content" [{"type" "text" "text" (tool-error-message error)}]}
                             (let [sc (->json (cond-> result
                                                (:message/receipt result) (assoc :accepted (get-in result [:message/receipt :receipt/status]))))]
                               {"structuredContent" sc
                                "content" [{"type" "text" "text" (js/JSON.stringify (clj->js sc))}]}))}})

    :else {:ledger lg :response (rpc-error id -32601 "method not found")}))

(defn handle-rpc
  "Handle an already decoded, string-keyed MCP JSON-RPC request.
   Notifications retain their ledger result and have no response."
  [lg ctx request]
  (cond-> (rpc-request lg ctx request)
    (not (contains? request "id")) (assoc :response nil)))
