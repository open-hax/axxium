(ns axxium.routes.mailbox-events
  "Opt-in MCP webhook subscription and delivery for a selected mailbox actor."
  (:require [axxium.domain.mailbox-events :as events]
            [axxium.domain.mailbox-mcp :as mailbox]
            [axxium.extern.agent-credentials :as secrets]
            [axxium.extern.http :as http]
            [axxium.extern.ledger-queue :as queue]
            [axxium.extern.mailbox-webhook :as webhook]
            [axxium.extern.token-crypto :as crypto]
            [axxium.law.did :as did]
            [axxium.law.principal :as principal]
            [axxium.routes.github-identity :as identity]
            [cljs.reader :as reader]))

(def event-methods #{"events/list" "events/subscribe" "events/unsubscribe"})

(def ^:private event-name "message.available")
(def ^:private max-ttl-ms 3600000)
(def ^:private verification-cache-ms 300000)

(def ^:private descriptor
  {"name" event-name
   "description" "A message is available in your Axxium mailbox."
   "delivery" ["webhook"]
   "inputSchema" {"type" "object" "properties" {} "additionalProperties" false}
   "payloadSchema" {"type" "object" "properties" {"message_id" {"type" "string"}}
                    "required" ["message_id"] "additionalProperties" false}})

(defn enabled? [runtime proof]
  (and runtime (= (:owner runtime) (principal/address (:principal proof)))))

(defn- success [id result]
  {:response {"jsonrpc" "2.0" "id" id "result" result}})

(defn- rpc-error
  ([id code message] (rpc-error id code message nil))
  ([id code message reason]
   {:response {"jsonrpc" "2.0" "id" id
               "error" (cond-> {"code" code "message" message}
                         reason (assoc "data" {"reason" reason}))}}))

(defn- parse-request [{:strs [method params]}]
  (let [subscribe? (= method "events/subscribe")
        delivery (get params "delivery")
        url (webhook/canonical-url (get delivery "url"))
        secret (get delivery "secret")
        ttl (get params "ttlMs")]
    (when (and (= event-name (get params "name"))
               (= {} (get params "arguments" {}))
               (every? (if subscribe? #{"name" "arguments" "delivery" "cursor" "ttlMs"}
                                      #{"name" "arguments" "delivery"})
                       (keys params))
               (map? delivery)
               (= "webhook" (get delivery "mode"))
               (= (set (keys delivery)) (if subscribe? #{"mode" "url" "secret"} #{"mode" "url"}))
               url
               (nil? (get params "cursor"))
               (or (nil? ttl) (and (int? ttl) (pos? ttl)))
               (or (not subscribe?) (webhook/valid-secret? secret)))
      {:url url :secret secret :ttl (min (or ttl max-ttl-ms) max-ttl-ms)})))

(defn- aad [{:keys [owner id generation]}]
  (pr-str ["axxium.mailbox.webhook.v1" owner id generation]))

(defn- open-binding [key subscription]
  (when-let [plaintext (crypto/open-sealed key (aad subscription) (:sealed subscription))]
    (try (reader/read-string plaintext)
         (catch :default _ nil))))

(defn- subscription-result [id subscription]
  (success id {"id" (:id subscription)
               "refreshBefore" (secrets/instant-iso (:expires subscription))
               "cursor" nil "truncated" false}))

(defn- ^:async submit-proof!
  [{:keys [writer path active-proof!]} proof f]
  (let [submit (fn []
                 (queue/submit! writer path
                   (fn ^:async authorize-operation [lg]
                     (if (await (active-proof! proof))
                       (f lg)
                       {:unauthenticated? true}))))]
    (if (:mailbox/owner-root-did proof)
      ;; Actor authority and mailbox writes always acquire identity first.
      (:result (await (identity/with-current-actor-state! (fn [_] (submit)))))
      (await (submit)))))

(defn- check-session! [runtime proof ctx f]
  (submit-proof! runtime proof
    (fn [lg]
      (if (mailbox/session-error lg (assoc ctx :now (secrets/now)))
        {:session-error true}
        (f lg)))))

(defn- ^:async verify-subscription!
  [{:keys [owner key] :as runtime} proof ctx rpc-id {:keys [url secret ttl]} previous
   {:keys [id cancelled?]}]
  (let [at (secrets/now)
        prior (when (= id (:id previous)) (open-binding key previous))
        same-key? (= secret (:secret prior))
        remaining (when previous (- (inst-ms (:expires previous)) (inst-ms at)))
        duplicate? (and same-key? (= proof (:proof prior)) remaining
                        (<= (/ ttl 2) remaining ttl))]
    (if duplicate?
      (subscription-result rpc-id previous)
      (let [cached? (and same-key? (number? (:verified-at prior))
                         (<= 0 (- (inst-ms at) (:verified-at prior)) verification-cache-ms))
            verification (if cached? {:verified? true}
                              (await (webhook/verify! {:id id :url url :secret secret})))]
        (if-not (:verified? verification)
          (rpc-error rpc-id -32015 "Callback verification failed" (:reason verification))
          (await (check-session! runtime proof ctx
                   (fn [lg]
                     (if (or @cancelled? (not= previous (events/subscription lg owner)))
                       (rpc-error rpc-id -32000 "Subscription changed during verification")
                       (let [at (secrets/now)
                             sub {:id id :owner owner :generation (secrets/secure-id)
                                  :at at :expires (secrets/instant-after at ttl)}
                             binding {:url url :secret secret :proof proof
                                      :verified-at (if cached? (:verified-at prior) (inst-ms at))}
                             out (events/subscribe lg (assoc sub :sealed (crypto/seal key (aad sub) (pr-str binding))))]
                         (if (:error out)
                           (rpc-error rpc-id -32000 "Subscription refused")
                           (merge (select-keys out [:ledger]) (subscription-result rpc-id (:result out))))))))))))))

(defn- ^:async subscribe!
  [{:keys [owner verifying?] :as runtime} proof ctx rpc-id {:keys [url] :as args}]
  (let [id (webhook/subscription-id owner url)
        pending {:id id :cancelled? (atom false)}
        preflight (await (check-session! runtime proof ctx
                           (fn [lg] {:subscription (events/subscription lg owner)
                                     :authorized? (events/authorized? lg owner (secrets/now))})))
        previous (:subscription preflight)]
    (cond
      (or (:session-error preflight) (:unauthenticated? preflight)) preflight
      (not (:authorized? preflight)) (rpc-error rpc-id -32001 "Mailbox read access is required")
      (and previous (not= id (:id previous))
           (> (inst-ms (:expires previous)) (secrets/now-ms)))
      (rpc-error rpc-id -32000 "Unsubscribe the existing callback first")
      (not (compare-and-set! verifying? nil pending))
      (rpc-error rpc-id -32015 "Callback verification is busy" "verification_busy")
      :else
      (try (await (verify-subscription! runtime proof ctx rpc-id args previous pending))
           (finally (reset! verifying? nil))))))

(defn ^:async execute!
  "Check the mailbox session before describing events or changing subscriptions."
  [{:keys [owner verifying?] :as runtime} proof ctx {:strs [id method params] :as body}]
  (if-not (enabled? runtime proof)
    (rpc-error id -32601 "method not found")
    (if (= method "events/list")
      (if (and (every? #{"cursor"} (keys params)) (nil? (get params "cursor")))
        (await (check-session! runtime proof ctx
                 (fn [lg]
                   (success id {"events" (if (events/authorized? lg owner (secrets/now))
                                            [descriptor] [])}))))
        (rpc-error id -32602 "Invalid event arguments"))
      (if-let [{:keys [url] :as args} (parse-request body)]
        (if (= method "events/subscribe")
          (await (subscribe! runtime proof ctx id args))
          (await (check-session! runtime proof ctx
                   (fn [lg]
                     (let [sub-id (webhook/subscription-id owner url)
                           pending @verifying?
                           out (events/unsubscribe lg owner sub-id (secrets/now))]
                       (when (= sub-id (:id pending)) (reset! (:cancelled? pending) true))
                       (merge (select-keys out [:ledger]) (success id {})))))))
        (rpc-error id -32602 "Invalid event arguments")))))

(defn- ^:async send-attempt! [{:keys [writer path active-proof!] :as runtime} candidate subscription {:keys [url secret proof]}]
  (let [attempt (await (submit-proof! runtime proof
                         (fn [lg] (events/attempt lg candidate (secrets/now)))))
        authorized? (and (:result attempt)
                         (or (not (:mailbox/owner-root-did proof)) (await (active-proof! proof))))
        lg (queue/current-ledger writer path)
        current (events/subscription lg (:owner candidate))
        now (secrets/now)]
    (when (and authorized?
               (= (:generation subscription) (:generation current))
               (> (inst-ms (:expires current)) (inst-ms now))
               (events/authorized? lg (:owner candidate) now))
      (let [response (await (webhook/deliver! {:id (:id subscription) :url url :secret secret}
                              {"eventId" (:event-id candidate) "name" event-name
                               "timestamp" (secrets/instant-iso (:message-at candidate))
                               "data" {"message_id" (:message-id candidate)} "cursor" nil}))]
        (await (queue/submit! writer path
                 (fn [lg] (events/settle lg candidate (:status response) (secrets/now)
                                        (:response-request-id response)))))))))

(defn- ^:async work! [{:keys [owner key writer path] :as runtime}]
  (let [lg (queue/current-ledger writer path)]
    (when-let [candidate (first (filter #(= owner (:owner %)) (events/due-deliveries lg (secrets/now))))]
      (let [subscription (events/subscription lg owner)
            binding (open-binding key subscription)]
        (when (and (:proof binding) (= (:generation candidate) (:generation subscription)))
          (await (send-attempt! runtime candidate subscription binding)))))))

(defn install!
  "Enable the bounded pilot only for an explicitly selected DID and private key."
  [app writer path owner key-file active-proof!]
  (when (or owner key-file)
    (let [key (when key-file (crypto/read-private-key! key-file))]
      (when-not (and (did/did? owner) (<= (count owner) 2048) key)
        (throw (ex-info "Mailbox webhook pilot requires a recipient DID and a private key file" {})))
      (let [runtime {:owner owner :key key :writer writer :path path
                     :active-proof! active-proof! :verifying? (atom nil)}]
        (http/on-interval! app 2500 #(work! runtime))
        runtime))))
