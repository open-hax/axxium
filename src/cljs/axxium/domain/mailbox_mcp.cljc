(ns axxium.domain.mailbox-mcp
  "Addressable mailbox MCP (docs/addressable-mailbox-mcp-mvp.md, contract
   from open-hax/axxium#47) on clio mailbox streams.

   Every tool is a pure (ledger, ctx, args) -> {:ledger :result | :error}.
   A call either appends its whole batch of events (decision, message,
   outbox entry) or nothing, so persisting the returned ledger value is one
   atomic write. The PDS is never used as a mailbox: messages live only on
   `mbox/<did>` streams here.

   ctx = {:principal (law.principal) :session/id :audience :now}. The
   principal comes from a verified AT Protocol sign-in or service token;
   nil means anonymous and every tool refuses."
  (:require [axxium.domain.authority :as authority]
            [axxium.domain.ledger :as ledger]
            [axxium.domain.mailbox :as mailbox]
            [axxium.domain.policy :as policy]
            [axxium.domain.resource :as resource]
            [axxium.domain.state :as state]
            [axxium.law.did :as did]
            [axxium.law.event :as law]
            [axxium.law.principal :as principal]
            [clojure.string :as str]))

(def empty-ledger ledger/empty-ledger)

(def spec-tools
  #{"whoami" "open_session" "resolve_address" "send_message" "read_inbox"
    "acknowledge_message" "get_delivery"})

(def outbox "outbox")
(def sessions "sessions")

;; ---------------------------------------------------------------- ids/time

(defn- nid
  "The id of the next event: derived from ledger position, so it is fresh
   (law 2) and deterministic for a given ledger value."
  ([lg] (nid lg 0))
  ([lg offset]
   (let [digits (str (+ offset (count lg) 1))]
     (str "a0000000-0000-4000-8000-" (apply str (repeat (- 12 (count digits)) "0")) digits))))

(defn- ms [t] (.getTime t))
(defn- plus-ms [t n] (js/Date. (+ (ms t) n)))

(defn- refuse
  ([error] {:error error})
  ([lg error] {:ledger lg :error error}))

;; ---------------------------------------------------------------- reads

(defn- of-type [lg stream type]
  (filter #(and (= stream (:event/stream %)) (= type (:event/type %))) lg))

(defn messages
  "Accepted messages in `did`'s mailbox, oldest first."
  [lg did]
  (mapv :event/body (of-type lg (mailbox/stream did) :axxium.mailbox/accepted)))

(defn- message-event [lg message-id]
  (some #(when (and (= :axxium.mailbox/accepted (:event/type %))
                    (= message-id (get-in % [:event/body :message/id]))) %)
        lg))

(defn- session [lg sid]
  (some #(when (= sid (:session/id (:event/body %))) (:event/body %))
        (of-type lg sessions :axxium.session/opened)))

;; ---------------------------------------------------------------- guards

(defn- authenticate
  "Fail closed: principal, session binding, audience and expiry."
  [lg {:keys [principal audience now] sid :session/id}]
  (let [s (when sid (session lg sid))]
    (cond
      (not (principal/authenticated? principal)) :unauthenticated
      (nil? s) :no-session
      (not= (:auth/did principal) (:session/did s)) :session-mismatch
      (not= audience (:session/aud s)) :wrong-audience
      (>= (ms now) (ms (:session/expires s))) :session-expired
      :else nil)))

(def ^:private secret-key
  #"(?i)(token|secret|password|passwd|cookie|authorization|api[_-]?key|credential|callback|private[_-]?key)")

(def ^:private secret-value
  #"(?i)^\s*(bearer\s|basic\s)|^eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.|-----BEGIN [A-Z ]*PRIVATE KEY-----")

(defn secret?
  "True when a payload carries something that looks like a credential."
  [x]
  (cond
    (map? x) (some (fn [[k v]] (or (re-find secret-key (if (keyword? k) (name k) (str k))) (secret? v))) x)
    (coll? x) (some secret? x)
    (string? x) (boolean (re-find secret-value x))
    :else false))

;; ---------------------------------------------------------------- tools

(defn- whoami [lg ctx _]
  (let [s (session lg (:session/id ctx))]
    {:ledger lg
     :result {:session/did (:session/did s) :session/method (:session/method s)
              :session/aud (:session/aud s) :session/expires (:session/expires s)
              :delivery/mode (if (:session/push? s) :push :poll)}}))

(defn- open-session [lg {:keys [principal audience now]} {aud :audience :keys [ttl-ms push]}]
  (cond
    (not (principal/authenticated? principal)) (refuse :unauthenticated)
    (not= aud audience) (refuse :wrong-audience)
    (not (pos-int? ttl-ms)) (refuse :bad-ttl)
    :else
    (let [sid (nid lg)
          body {:session/id sid :session/did (:auth/did principal)
                :session/method (:auth/method principal) :session/aud aud
                :session/expires (plus-ms now (min ttl-ms 86400000))
                :session/push? (true? push)}
          [lg' _] (ledger/emit lg {:id sid :stream sessions :type :axxium.session/opened
                                   :actor (:auth/did principal) :at now :body body})]
      {:ledger lg' :result (assoc body :delivery/mode (if (:session/push? body) :push :poll))})))

(defn- resolve-address [lg _ {:keys [address]}]
  (let [d (if (and (string? address) (str/starts-with? address mailbox/prefix))
            (subs address (count mailbox/prefix)) address)]
    (if (did/did? d)
      {:ledger lg
       :result {:address/did d :address/mailbox (mailbox/stream d)
                :address/registered? (contains? (:resources (state/fold lg)) (mailbox/stream d))}}
      (refuse lg :bad-address))))

(defn- register-mailbox [lg {:keys [principal now]} _]
  (let [me (:auth/did principal)
        box (mailbox/stream me)]
    (if (contains? (:resources (state/fold lg)) box)
      {:ledger lg :result {:mailbox box}}
      (let [r {:resource/id box :resource/kind :resource/mailbox :resource/owner me
               :resource/store :clio :resource/location {:stream box}}
            {lg1 :ledger e1 :effect} (resource/register lg (state/fold lg) me r
                                                        {:decision-id (nid lg) :effect-id (nid lg 1) :at now})
            g {:grant/id (str "mbox-grant:" (nid lg1 1)) :grant/actor me :grant/resource box
               :grant/actions [:send :read :ack]}
            {lg2 :ledger e2 :effect} (when e1
                                       (resource/issue-grant lg1 (state/fold lg1) me g
                                                             {:decision-id (nid lg1) :effect-id (nid lg1 1) :at now}))]
        (if e2 {:ledger lg2 :result {:mailbox box :grant/id (:grant/id g)}} (refuse lg :forbidden))))))

(defn- grant-send [lg {:keys [principal now]} {:keys [to]}]
  (if-not (did/did? to)
    (refuse :bad-address)
    (let [me (:auth/did principal)
          g {:grant/id (str "mbox-send:" (nid lg 1)) :grant/actor to
             :grant/resource (mailbox/stream me) :grant/actions [:send]}
          {lg' :ledger e :effect} (resource/issue-grant lg (state/fold lg) me g
                                                        {:decision-id (nid lg) :effect-id (nid lg 1) :at now})]
      (if e {:ledger lg' :result {:grant/id (:grant/id g)}} (refuse lg' :forbidden)))))

(defn- revoke-send [lg {:keys [principal now]} {:keys [to]}]
  (let [me (:auth/did principal)
        rv {:revoke/id (str "mbox-revoke:" (nid lg 1)) :revoke/actor to
            :revoke/resource (mailbox/stream me) :revoke/actions [:send] :revoke/at now}
        {lg' :ledger e :effect} (resource/issue-revoke lg (state/fold lg) me rv
                                                       {:decision-id (nid lg) :effect-id (nid lg 1) :at now})]
    (if e {:ledger lg' :result {:revoke/id (:revoke/id rv)}} (refuse lg' :forbidden))))

(defn- prior-send [lg from key]
  (some #(let [b (:event/body %)]
           (when (and (= from (:message/from b)) (= key (:message/idempotency-key b))) b))
        (filter #(= :axxium.mailbox/accepted (:event/type %)) lg)))

(defn- send-message [lg {:keys [principal now]} {:keys [to from idempotency-key body]}]
  (let [me (:auth/did principal)]
    (cond
      (and (some? from) (not= from me)) (refuse :forged-sender)
      (not (did/did? to)) (refuse :bad-address)
      (not (and (string? idempotency-key) (<= 1 (count idempotency-key) 256))) (refuse :bad-idempotency-key)
      (not (map? body)) (refuse :bad-body)
      (secret? body) (refuse :secret-in-payload)
      :else
      (if-let [prior (prior-send lg me idempotency-key)]
        (if (and (= to (:message/to prior)) (= body (:message/body prior)))
          {:ledger lg :result (select-keys prior [:message/id :message/receipt])
           :replayed? true}
          (refuse :idempotency-conflict))
        (let [box (mailbox/stream to)
              mid (nid lg 1)
              receipt {:receipt/message-id mid :receipt/mailbox box
                       :receipt/seq (law/next-seq lg box) :receipt/accepted-at now
                       :receipt/status :accepted}
              msg {:message/id mid :message/from me :message/to to :message/body body
                   :message/idempotency-key idempotency-key :message/owner to
                   :message/receipt receipt}
              {lg1 :ledger e :effect}
              (policy/act lg (state/fold lg)
                          {:actor me :resource box :action :send :at now}
                          {:stream box :type :axxium.mailbox/accepted :actor me :body msg}
                          {:decision-id (nid lg) :effect-id mid :at now})]
          (if-not e
            (refuse lg1 :forbidden)
            (let [[lg2 _] (ledger/emit lg1 {:id (nid lg1) :stream outbox :type :axxium.outbox/pending
                                            :actor me :at now :causes [mid]
                                            :body {:outbox/message-id mid :outbox/recipient to}})]
              {:ledger lg2 :result {:message/id mid :message/receipt receipt}})))))))

(defn- acked-ids
  "Message ids acknowledged on `did`'s mailbox. Acks are per message: an
   ack names one message and implies nothing about any other."
  [lg did]
  (into #{} (map #(get-in % [:event/body :ack/message-id]))
        (of-type lg (mailbox/stream did) :axxium.mailbox/acknowledged)))

(defn- ack-cursor
  "Highest seq s such that every message at or below s is acknowledged
   (the contiguous acknowledged prefix). Acking a later message never moves
   the cursor past an earlier unacknowledged one."
  [lg did]
  (let [acked (acked-ids lg did)]
    (reduce (fn [cursor e]
              (if (acked (get-in e [:event/body :message/id]))
                (:event/seq e)
                (reduced cursor)))
            0
            (of-type lg (mailbox/stream did) :axxium.mailbox/accepted))))

(defn- read-inbox [lg {:keys [principal now]} {:keys [mailbox after]}]
  (let [me (:auth/did principal)
        owner (or mailbox me)
        box (mailbox/stream owner)
        {lg' :ledger d :decision} (policy/act lg (state/fold lg)
                                              {:actor me :resource box :action :read :at now}
                                              nil {:decision-id (nid lg) :at now})]
    (if-not (get-in d [:event/body :decision/allow?])
      (refuse lg' :forbidden)
      (let [explicit? (int? after)
            cursor (if explicit? after (ack-cursor lg owner))
            acked (if explicit? #{} (acked-ids lg owner))
            es (->> (of-type lg box :axxium.mailbox/accepted)
                    (filter #(> (:event/seq %) cursor))
                    (remove #(acked (get-in % [:event/body :message/id]))))]
        {:ledger lg'
         :result {:cursor cursor
                  :next-cursor (if (seq es) (:event/seq (last es)) cursor)
                  :messages (mapv #(assoc (:event/body %) :message/seq (:event/seq %)) es)}}))))

(defn- acknowledge [lg {:keys [principal now]} {:keys [message-id]}]
  (let [me (:auth/did principal)
        box (mailbox/stream me)
        e (message-event lg message-id)]
    (if (not= box (:event/stream e))
      (refuse :not-found)
      (let [{lg' :ledger eff :effect}
            (policy/act lg (state/fold lg) {:actor me :resource box :action :ack :at now}
                        {:stream box :type :axxium.mailbox/acknowledged :actor me
                         :body {:ack/message-id message-id :ack/seq (:event/seq e)}}
                        {:decision-id (nid lg) :effect-id (nid lg 1) :at now})]
        (if eff
          {:ledger lg' :result {:ack/message-id message-id :cursor (:event/seq e)}}
          (refuse lg' :forbidden))))))

(defn- outbox-body [lg type message-id]
  (some #(when (= message-id (get-in % [:event/body :outbox/message-id])) (:event/body %))
        (of-type lg outbox type)))

(defn- notification-of
  "Reported notification state. Planning (:axxium.outbox/dispatched) only
   chooses a channel; the state becomes :delivered only once an external
   transport acknowledgment (:axxium.outbox/delivered) is recorded."
  [lg message-id]
  (if (outbox-body lg :axxium.outbox/delivered message-id) :delivered :pending))

(defn- get-delivery [lg {:keys [principal]} {:keys [message-id]}]
  (let [me (:auth/did principal)
        m (:event/body (message-event lg message-id))]
    (if-not (and m (#{(:message/from m) (:message/to m)} me))
      (refuse :not-found)
      {:ledger lg
       :result {:message/id message-id
                :delivery/accepted (get-in m [:message/receipt :receipt/status])
                :delivery/receipt (:message/receipt m)
                :delivery/notification (notification-of lg message-id)
                :delivery/notification-plan
                (:outbox/channel (outbox-body lg :axxium.outbox/dispatched message-id))
                :delivery/acknowledged?
                (boolean (some #(= message-id (get-in % [:event/body :ack/message-id]))
                               (of-type lg (mailbox/stream (:message/to m)) :axxium.mailbox/acknowledged)))
                ;; Law: accepting or notifying a message says nothing about
                ;; whether the recipient executed it.
                :delivery/execution :unknown}})))

(def ^:private tools
  {:whoami whoami :resolve_address resolve-address :send_message send-message
   :read_inbox read-inbox :acknowledge_message acknowledge :get_delivery get-delivery
   :register_mailbox register-mailbox :grant_send grant-send :revoke_send revoke-send})

(defn call
  "Run one tool. Returns {:ledger :result} or {:ledger :error}; on refusal
   the ledger holds at most the recorded refusal decision."
  [lg ctx tool args]
  (if (= tool :open_session)
    (let [r (open-session lg ctx args)] (update r :ledger #(or % lg)))
    (if-let [f (get tools tool)]
      (if-let [err (authenticate lg ctx)]
        (refuse lg err)
        (update (f lg ctx args) :ledger #(or % lg)))
      (refuse lg :unknown-tool))))

;; ---------------------------------------------------------------- outbox

(defn- push-subscriber? [lg did now]
  (some #(let [s (:event/body %)]
           (and (= did (:session/did s)) (:session/push? s)
                (< (ms now) (ms (:session/expires s)))))
        (of-type lg sessions :axxium.session/opened)))

(defn dispatch
  "Drain the durable outbox. Each pending entry is dispatched exactly once:
   replaying after a crash re-sends only what has no :dispatched record.
   Push goes only to explicitly subscribed recipients; everyone else is
   marked :poll and gets no push claim. Notifications carry no payload."
  [lg now]
  (let [done (into #{} (map #(get-in % [:event/body :outbox/message-id]))
                   (of-type lg outbox :axxium.outbox/dispatched))
        pending (remove #(done (get-in % [:event/body :outbox/message-id]))
                        (of-type lg outbox :axxium.outbox/pending))]
    (reduce (fn [{l :ledger :as acc} p]
              (let [{:outbox/keys [message-id recipient]} (:event/body p)
                    channel (if (push-subscriber? l recipient now) :push :poll)
                    [l' _] (ledger/emit l {:id (nid l) :stream outbox :type :axxium.outbox/dispatched
                                           :actor recipient :at now :causes [(:event/id p)]
                                           :body {:outbox/message-id message-id :outbox/channel channel}})]
                (cond-> (assoc acc :ledger l')
                  (= :push channel)
                  (update :notifications conj {:event "message.available" :recipient recipient
                                               :message/id message-id}))))
            {:ledger lg :notifications []}
            pending)))

;; Record an external transport's acknowledgment for a planned push. This
;; is the only event that moves :delivery/notification off :pending.
(defn confirm-delivery
  "Append :axxium.outbox/delivered for a planned notification. Idempotent;
   refuses when nothing was planned."
  [lg message-id {:keys [at transport-receipt]}]
  (cond
    (outbox-body lg :axxium.outbox/delivered message-id) {:ledger lg :replayed? true}
    (not (outbox-body lg :axxium.outbox/dispatched message-id)) (refuse lg :not-planned)
    :else
    (let [planned (some #(when (= message-id (get-in % [:event/body :outbox/message-id])) %)
                        (of-type lg outbox :axxium.outbox/dispatched))
          [lg' _] (ledger/emit lg {:id (nid lg) :stream outbox :type :axxium.outbox/delivered
                                   :actor (:event/actor planned) :at at :causes [(:event/id planned)]
                                   :body (cond-> {:outbox/message-id message-id}
                                           transport-receipt (assoc :outbox/transport-receipt transport-receipt))})]
      {:ledger lg' :result {:outbox/message-id message-id :delivery/notification :delivered}})))

;; ---------------------------------------------------------------- MCP JSON-RPC

(def ^:private str-schema {"type" "string"})

(defn- tool [name desc props required]
  {"name" name "description" desc
   "inputSchema" {"type" "object" "properties" props "required" required
                  "additionalProperties" false}})

(def tool-descriptors
  [(tool "whoami" "The authenticated actor and session." {} [])
   (tool "open_session" "Bind a session to this actor and audience; push only if the client declares it."
         {"audience" str-schema "ttl_ms" {"type" "integer"} "push" {"type" "boolean"}} ["audience" "ttl_ms"])
   (tool "resolve_address" "Resolve a DID or mbox/<did> to a mailbox." {"address" str-schema} ["address"])
   (tool "send_message" "Send to a mailbox. Repeating an idempotency key with the same body returns the original receipt."
         {"to" str-schema "from" str-schema "idempotency_key" str-schema "body" {"type" "object"}}
         ["to" "idempotency_key" "body"])
   (tool "read_inbox" "Read messages after a cursor (default: last acknowledged)."
         {"mailbox" str-schema "after" {"type" "integer"}} [])
   (tool "acknowledge_message" "Acknowledge a message; advances the durable cursor."
         {"message_id" str-schema} ["message_id"])
   (tool "get_delivery" "Acceptance, notification and acknowledgement state. Never claims execution."
         {"message_id" str-schema} ["message_id"])
   (tool "register_mailbox" "Register your mailbox and its owner grant." {} [])
   (tool "grant_send" "Allow an actor to send to your mailbox." {"to" str-schema} ["to"])
   (tool "revoke_send" "Revoke an actor's send grant on your mailbox." {"to" str-schema} ["to"])])

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

(defn ->json [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(if (keyword? k) (json-key k) (str k))
                                        (if (opaque-keys k) (payload->json v) (->json v))])) x)
    (coll? x) (mapv ->json x)
    (keyword? x) (name x)
    (inst? x) (.toISOString x)
    :else x))

(defn- rpc-error [id code message]
  {"jsonrpc" "2.0" "id" id "error" {"code" code "message" message}})

(defn handle-rpc
  "MCP JSON-RPC over an authenticated transport. Returns {:ledger :response}."
  [lg ctx {:strs [id method params]}]
  (cond
    (not (principal/authenticated? (:principal ctx)))
    {:ledger lg :response (rpc-error id -32001 "unauthenticated")}

    (= method "initialize")
    {:ledger lg :response {"jsonrpc" "2.0" "id" id
                           "result" {"protocolVersion" "2025-06-18"
                                     "serverInfo" {"name" "axxium-mailbox" "version" "0.1.0"}
                                     "capabilities" {"tools" {}}}}}

    (= method "tools/list")
    {:ledger lg :response {"jsonrpc" "2.0" "id" id "result" {"tools" tool-descriptors}}}

    (= method "tools/call")
    (let [tool-name (get params "name")
          args (into {} (map (fn [[k v]] [(arg-key k) (if (= k "body") (js->clj v) v)]))
                     (get params "arguments"))
          {:keys [ledger result error]} (call lg ctx (keyword tool-name) args)]
      {:ledger ledger
       :response {"jsonrpc" "2.0" "id" id
                  "result" (if error
                             {"isError" true "content" [{"type" "text" "text" (name error)}]}
                             (let [sc (->json (cond-> result
                                                (:message/receipt result) (assoc :accepted (get-in result [:message/receipt :receipt/status]))))]
                               {"structuredContent" sc
                                "content" [{"type" "text" "text" (js/JSON.stringify (clj->js sc))}]}))}})

    :else {:ledger lg :response (rpc-error id -32601 "method not found")}))
