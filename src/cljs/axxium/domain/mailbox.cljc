(ns axxium.domain.mailbox
  "clio mailbox streams. `mbox/<did>` holds every message addressed to that
   DID, as clio events in Katamorph's message shape. A send is:
   authenticate (law 8) -> decide :send on the mailbox resource -> record the
   decision -> append the message (law 7)."
  (:require [axxium.domain.ledger :as ledger]
            [axxium.domain.policy :as policy]
            [axxium.law.did :as did]
            [axxium.law.principal :as principal]))

(def prefix "mbox/")

(defn stream
  "mbox : Did -> Stream"
  [recipient]
  (str prefix recipient))

(defn mailbox-resource [recipient] (stream recipient))

(defn- own-mailbox?
  "The owner may always act on their own mailbox."
  [_state {:keys [actor resource]}]
  (= resource (mailbox-resource actor)))

(defn send-message
  "Send `body` from `sender` (an authenticated principal) to `to`.
   `ids` = {:decision-id :message-id :at :message/id}. Returns
   {:ledger :decision :effect}; :effect is nil when refused."
  [lg state sender to body {:keys [decision-id message-id at] :as ids}]
  (when-not (principal/authenticated? sender)
    (throw (ex-info "Mailbox send requires an authenticated sender"
                    {:axxium/error :axxium.mbox/unauthenticated})))
  (when-not (did/did? to)
    (throw (ex-info "Recipient must be a DID" {:axxium/error :axxium.mbox/bad-recipient})))
  (when-not (map? body)
    (throw (ex-info "Message body must be a map" {:axxium/error :axxium.mbox/bad-body})))
  (policy/act lg state
              {:actor (:auth/did sender) :resource (mailbox-resource to)
               :action :send :at at}
              {:stream (stream to)
               :type :axxium.mbox/sent
               :actor (:auth/did sender)
               :body (cond-> {:msg/to to
                              :msg/auth (:auth/method sender)
                              :message/body body}
                       (:message/id ids) (assoc :message/id (:message/id ids)))}
              {:decision-id decision-id :effect-id message-id :at at}
              own-mailbox?))

(defn read-mailbox
  "Messages in `owner`'s mailbox, after an allowed :read decision. Reading is
   recorded too, so who looked at a mailbox is itself in the log."
  [lg state reader {:keys [decision-id at]}]
  (let [{:keys [ledger decision]}
        (policy/act lg state
                    {:actor (:auth/did reader) :resource (mailbox-resource (:auth/did reader))
                     :action :read :at at}
                    nil {:decision-id decision-id :at at} own-mailbox?)]
    {:ledger ledger
     :decision decision
     :messages (when (get-in decision [:event/body :decision/allow?])
                 (ledger/stream ledger (stream (:auth/did reader))))}))
