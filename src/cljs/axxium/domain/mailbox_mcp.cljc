(ns axxium.domain.mailbox-mcp
  "Addressable mailbox MCP (docs/addressable-mailbox-mcp-mvp.md, contract
   from open-hax/axxium#47) on clio mailbox streams.

   Every tool is a pure (ledger, ctx, args) -> {:ledger :result | :error}.
   A pure call appends its whole batch of events (decision, message,
   outbox entry) or nothing. Durable batch atomicity is a separate store
   obligation. The PDS is never used as a mailbox: messages live only on
   `mbox/<did>` streams here.

   ctx = {:principal (law.principal) :session/id :audience :now}. The
   principal comes from a verified AT Protocol sign-in or service token;
   nil means anonymous and every tool refuses."
  (:require [axxium.domain.ledger :as ledger]
            [axxium.domain.mailbox :as mailbox]
            [axxium.domain.policy :as policy]
            [axxium.domain.resource :as resource]
            [axxium.domain.state :as state]
            [axxium.law.did :as did]
            [axxium.law.event :as law]
            [axxium.law.principal :as principal]
            [clio.shape.canonical :as canonical]
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

(defn- plus-ms
  "Add milliseconds using each host's EDN instant representation."
  [instant millis]
  (let [epoch-ms (+ (inst-ms instant) millis)]
    #?(:clj (java.util.Date. epoch-ms)
       :cljs (js/Date. epoch-ms))))

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

(defn- accepted-events [lg]
  (filter #(= :axxium.mailbox/accepted (:event/type %)) lg))

(defn- message-event [lg message-id]
  (some #(when (and (= :axxium.mailbox/accepted (:event/type %))
                    (= message-id (get-in % [:event/body :message/id]))) %)
        lg))

(defn- session [lg sid]
  (some #(when (= sid (:session/id (:event/body %))) (:event/body %))
        (of-type lg sessions :axxium.session/opened)))

;; ---------------------------------------------------------------- guards

(defn session-error
  "Fail closed: principal, session binding, audience and expiry."
  [lg {:keys [principal audience now] sid :session/id :as ctx}]
  (let [s (when sid (session lg sid))]
    (cond
      (not (principal/authenticated? principal)) :unauthenticated
      (nil? s) :no-session
      (not= (:auth/did principal) (:session/did s)) :session-mismatch
      (not= audience (:session/aud s)) :wrong-audience
      (not= (:session/binding s) (:session/binding ctx)) :session-mismatch
      (>= (inst-ms now) (inst-ms (:session/expires s))) :session-expired
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

(defn- session-result [lg body]
  {:ledger lg
   :result (assoc (dissoc body :session/binding)
                  :delivery/mode (if (:session/push? body) :push :poll))})

(defn- open-session [lg {:keys [principal audience now] :as ctx} {aud :audience :keys [ttl-ms push]}]
  (cond
    (not (principal/authenticated? principal)) (refuse :unauthenticated)
    (not= aud audience) (refuse :wrong-audience)
    (not (pos-int? ttl-ms)) (refuse :bad-ttl)
    (:session/current-only? ctx)
    (if-let [error (session-error lg ctx)]
      (refuse lg error)
      (session-result lg (session lg (:session/id ctx))))
    :else
    (let [sid (or (:session/new-id ctx) (nid lg))
          body (cond-> {:session/id sid :session/did (:auth/did principal)
                        :session/method (:auth/method principal) :session/aud aud
                        :session/expires (plus-ms now (min ttl-ms 86400000))
                        :session/push? (and (not= false (:session/push-supported? ctx))
                                            (true? push))}
                 (:session/binding ctx) (assoc :session/binding (:session/binding ctx)))
          [lg' _] (ledger/emit lg {:id sid :stream sessions :type :axxium.session/opened
                                   :actor (:auth/did principal) :at now :body body})]
      (session-result lg' body))))

(defn- resolve-address [lg _ {:keys [address]}]
  (let [d (if (and (string? address) (str/starts-with? address mailbox/prefix))
            (subs address (count mailbox/prefix)) address)]
    (if (did/did? d)
      {:ledger lg
       :result {:address/did d :address/mailbox (mailbox/stream d)
                :address/registered? (contains? (:resources (state/fold lg)) (mailbox/stream d))}}
      (refuse lg :bad-address))))

(def ^:private owner-actions [:send :read :ack])

(defn- owner-grant-issued? [lg owner box]
  ;; Look at grant history so a retry never recreates a revoked grant.
  (some (fn [{:event/keys [actor body]}]
          (and (= owner actor (:grant/actor body))
               (= box (:grant/resource body))
               (str/starts-with? (or (:grant/id body) "") "mbox-grant:")))
        (of-type lg "grants" :axxium.grant/granted)))

(defn- unrevoked-owner-actions [lg owner box]
  (let [revoked (into #{}
                      (mapcat (fn [{:event/keys [body]}]
                                (when (and (= owner (:revoke/actor body))
                                           (= box (:revoke/resource body)))
                                  (or (:revoke/actions body) owner-actions))))
                      (of-type lg "grants" :axxium.grant/revoked))]
    (filterv (complement revoked) owner-actions)))

(defn- register-mailbox [lg {:keys [principal now]} _]
  (let [me (:auth/did principal)
        box (mailbox/stream me)
        st (state/fold lg)
        registered? (contains? (:resources st) box)
        actions (if registered?
                  (when-not (owner-grant-issued? lg me box)
                    (unrevoked-owner-actions lg me box))
                  owner-actions)]
    (if-not (seq actions)
      {:ledger lg :result {:mailbox box}}
      (let [{lg1 :ledger registration :effect}
            (when-not registered?
              (resource/register lg st me {:resource/id box :resource/kind :resource/mailbox
                                           :resource/owner me :resource/store :clio
                                           :resource/location {:stream box}}
                                 {:decision-id (nid lg) :effect-id (nid lg 1) :at now}))]
        (if (or registered? registration)
          (let [lg1 (or lg1 lg)
                g {:grant/id (str "mbox-grant:" (nid lg1 1)) :grant/actor me :grant/resource box
                   :grant/actions actions}
                {lg2 :ledger grant :effect}
                (resource/issue-grant lg1 (if registered? st (state/fold lg1)) me g
                                      {:decision-id (nid lg1) :effect-id (nid lg1 1) :at now})]
            (if grant {:ledger lg2 :result {:mailbox box :grant/id (:grant/id g)}} (refuse lg :forbidden)))
          (refuse lg :forbidden))))))

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
        (accepted-events lg)))

(defn- portable-edn? [value]
  (try
    (canonical/canonical-form value)
    true
    (catch #?(:clj Exception :cljs :default) _ false)))

(defn- send-message [lg {:keys [principal now]} {:keys [to from idempotency-key body]}]
  (let [me (:auth/did principal)]
    (cond
      (and (some? from) (not= from me)) (refuse :forged-sender)
      (not (did/did? to)) (refuse :bad-address)
      (not (and (string? idempotency-key) (<= 1 (count idempotency-key) 256)
                (portable-edn? idempotency-key))) (refuse :bad-idempotency-key)
      (not (and (map? body) (portable-edn? body))) (refuse :bad-body)
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
            durable-cursor (ack-cursor lg owner)
            cursor (if explicit? (min after durable-cursor) durable-cursor)
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
          {:ledger lg' :result {:ack/message-id message-id :cursor (ack-cursor lg' me)}}
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
      (if-let [err (session-error lg ctx)]
        (refuse lg err)
        (update (f lg ctx args) :ledger #(or % lg)))
      (refuse lg :unknown-tool))))

;; ---------------------------------------------------------------- outbox

(defn- push-subscriber? [lg did now]
  (some #(let [s (:event/body %)]
           (and (= did (:session/did s)) (:session/push? s)
                (< (inst-ms now) (inst-ms (:session/expires s)))))
        (of-type lg sessions :axxium.session/opened)))

(defn dispatch
  "Plan each accepted message once per persisted ledger, even if its pending
   event was lost in a partial write. Push is at-most-once:
   a persisted plan suppresses replay even if a crash prevented its send.
   Planning and external sending are not atomic; durable polling is the fallback.
   Push goes only to explicitly subscribed recipients; everyone else is
   marked :poll and gets no push claim. Notifications carry no payload."
  [lg now]
  (let [done (into #{} (map #(get-in % [:event/body :outbox/message-id]))
                   (of-type lg outbox :axxium.outbox/dispatched))
        pending-ids (into {} (map (juxt #(get-in % [:event/body :outbox/message-id]) :event/id))
                          (of-type lg outbox :axxium.outbox/pending))
        pending (remove #(done (get-in % [:event/body :message/id])) (accepted-events lg))]
    (reduce (fn [{l :ledger :as acc} p]
              (let [{message-id :message/id recipient :message/to} (:event/body p)
                    cause (get pending-ids message-id (:event/id p))
                    channel (if (push-subscriber? l recipient now) :push :poll)
                    [l' _] (ledger/emit l {:id (nid l) :stream outbox :type :axxium.outbox/dispatched
                                           :actor recipient :at now :causes [cause]
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
   refuses when no push was planned, including polling-only plans."
  [lg message-id {:keys [at transport-receipt]}]
  (cond
    (outbox-body lg :axxium.outbox/delivered message-id) {:ledger lg :replayed? true}
    (not= :push (:outbox/channel (outbox-body lg :axxium.outbox/dispatched message-id)))
    (refuse lg :not-planned)
    :else
    (let [planned (some #(when (= message-id (get-in % [:event/body :outbox/message-id])) %)
                        (of-type lg outbox :axxium.outbox/dispatched))
          [lg' _] (ledger/emit lg {:id (nid lg) :stream outbox :type :axxium.outbox/delivered
                                   :actor (:event/actor planned) :at at :causes [(:event/id planned)]
                                   :body (cond-> {:outbox/message-id message-id}
                                           transport-receipt (assoc :outbox/transport-receipt transport-receipt))})]
      {:ledger lg' :result {:outbox/message-id message-id :delivery/notification :delivered}})))
