(ns axxium.law.oidc
  "Registered browser-client and private signing configuration contracts."
  (:require [axxium.law.did :as did]
            [axxium.law.relay :as relay]
            [clojure.string :as str]
            [malli.core :as m]))

(def mailbox-scope
  "The required event resource scope for the dedicated mailbox client."
  "mailbox:events")

(def mailbox-actors-scope
  "Opt-in discovery and messaging within the configured human's actor tree."
  "mailbox:actors")

(def mailbox-inbox-scope
  "Opt-in on-demand reading and acknowledgment of the delegated actor's own inbox."
  "mailbox:inbox")

(defn mailbox-resource-scopes
  "Resource scopes offered by the trusted mailbox client configuration."
  [{:keys [mailbox-actor-messaging? mailbox-inbox?]}]
  (cond-> [mailbox-scope]
    (true? mailbox-actor-messaging?) (conj mailbox-actors-scope)
    (true? mailbox-inbox?) (conj mailbox-inbox-scope)))

(defn- token-scopes [scope]
  (when (string? scope) (set (str/split scope #" "))))

(defn mailbox-actor-scope?
  "Check whether a space-delimited opaque token scope includes actor messaging."
  [scope]
  (contains? (token-scopes scope) mailbox-actors-scope))

(defn mailbox-inbox-scope?
  "Check whether a space-delimited scope explicitly includes inbox consumption."
  [scope]
  (contains? (token-scopes scope) mailbox-inbox-scope))

(defn mailbox-bound-actor-did?
  "Admit a bounded seeded actor DID to pin the actor-messaging grant."
  [value]
  (and (did/web? value) (<= (count value) 2048)))

(def registry
  "Named input contracts; private keys and client secrets stay in server config."
  {::id [:string {:min 1 :max 128}]
   ::secret [:string {:min 32 :max 512}]
   ::uri [:string {:min 1 :max 2048}]
   ::client [:map {:closed true}
             [:id [:ref ::id]]
             [:secret [:ref ::secret]]
             [:redirect-uris [:vector {:min 1 :max 16} [:ref ::uri]]]
             [:refresh-enabled? {:optional true} :boolean]
             [:device-enabled? {:optional true} :boolean]]
   ::config [:map {:closed true}
             [:issuer [:ref ::uri]]
             [:clients [:vector {:min 1 :max 64} [:ref ::client]]]
             [:cookie-keys [:vector {:min 1 :max 3} [:ref ::secret]]]
             [:jwks :map]
             [:allow-loopback? {:optional true} :boolean]
             [:device-flow-enabled? {:optional true} :boolean]
             [:mailbox-client-id {:optional true} [:ref ::id]]
             [:mailbox-resource {:optional true} [:ref ::uri]]
             [:mailbox-actor-messaging? {:optional true} :boolean]
             [:mailbox-inbox? {:optional true} :boolean]
             [:mailbox-owner-consent? {:optional true} :boolean]
             [:node-grants {:optional true} [:vector {:max 64} relay/grant-schema]]]})

(defn mailbox-client?
  "Check the exact opt-in registered client identity."
  [{:keys [mailbox-client-id mailbox-resource]} client-id]
  (and (some? mailbox-client-id) (some? mailbox-resource)
       (= mailbox-client-id client-id)))

(defn- mailbox-client-configured?
  [{:keys [clients mailbox-client-id mailbox-resource mailbox-actor-messaging?] :as config}]
  (or (and (nil? mailbox-client-id) (nil? mailbox-resource) (not mailbox-actor-messaging?))
      (some (fn [{:keys [id redirect-uris device-enabled?]}]
              (and (mailbox-client? config id)
                   (= 1 (count redirect-uris))
                   (not (true? device-enabled?))))
            clients)))

(defn require-config!
  "Validate closed config and unique client IDs before any provider is created.
   URL syntax and key material validation are owned by the crypto/URL adapter.
   Error data intentionally excludes private config values."
  [config]
  (when-not (and (m/validate [:ref ::config] config
                            {:registry (merge (m/default-schemas) registry)})
                 (= (count (:clients config)) (count (set (map :id (:clients config)))))
                 (= (count (:clients config)) (count (set (map :secret (:clients config)))))
                 (mailbox-client-configured? config)
                 (or (not (:mailbox-owner-consent? config)) (:mailbox-actor-messaging? config))
                 (or (not (:mailbox-inbox? config)) (:mailbox-owner-consent? config))
                 (relay/grants-valid? (get config :node-grants []) (:clients config)))
    (throw (ex-info "Invalid OIDC authority configuration" {})))
  config)

(defn- admitted-mailbox-scopes?
  [config scopes]
  (and (contains? scopes mailbox-scope)
       (or (not (contains? scopes mailbox-inbox-scope))
           (and (true? (:mailbox-owner-consent? config))
                (contains? scopes mailbox-actors-scope)))))

(defn mailbox-access-claims?
  "Admit only verified token claims for the exact client, issuer and resource."
  [{:keys [issuer mailbox-resource] :as config}
   {:keys [client-id token-issuer audience scope account-id grant-id]}]
  (let [scopes (token-scopes scope)]
    (and (mailbox-client? config client-id)
         (= issuer token-issuer)
         (= mailbox-resource audience)
         (admitted-mailbox-scopes? config scopes)
         (every? (set (mailbox-resource-scopes config)) scopes)
         (every? #(and (string? %) (<= 1 (count %) 2048)) [account-id grant-id]))))

(defn- consent-scopes?
  [scopes mailbox-client? config]
  (and (vector? scopes)
       (some #{"openid"} scopes)
       (if mailbox-client?
         (and (admitted-mailbox-scopes? config (set scopes))
              (every? (into #{"openid" "profile"} (mailbox-resource-scopes config)) scopes))
         (every? #{"openid" "profile"} scopes))))

(defn interaction-admission
  "Admit only a current principal's exact interaction and bounded profile scope.
   Context contains decoded scalars, never request objects or provider handles."
  [{:keys [expected-uid actual-uid actor-id session-actor-id prompt decision scopes claims mailbox-client?]
    :as context}]
  (cond
    (or (not (and (string? expected-uid) (re-matches #"[A-Za-z0-9_-]{1,128}" expected-uid)))
        (not= expected-uid actual-uid))
    {:decision :refuse :status 400}

    (not (and (string? actor-id) (seq actor-id)))
    {:decision :refuse :status 401}

    (= "deny" decision) {:decision :deny}
    (not= "approve" decision) {:decision :refuse :status 400}
    (= "login" prompt) {:decision :login}
    (not= "consent" prompt)
    {:decision :refuse :status 409}

    (not (and (consent-scopes? scopes mailbox-client? context)
              (vector? claims) (every? #{"sub" "name"} claims)))
    {:decision :refuse :status 403}

    (not= actor-id session-actor-id) {:decision :restart-login}
    :else {:decision :consent}))

(defn bounded-token-lifetime
  "Pure issuance contract: a token cannot extend the current stored parent deadline."
  [expires-at-ms now-ms maximum-seconds]
  (let [seconds (when (number? expires-at-ms)
                  (long (quot (- expires-at-ms now-ms) 1000)))]
    (when (and seconds (pos? seconds)) (min maximum-seconds seconds))))

(defn device-login-return
  "Resume only initial verification and one bounded public code; otherwise restart it."
  [value]
  (if (and (string? value)
           (re-matches #"/oidc/device(?:\?user_code=[A-Za-z0-9-]{1,64})?" value))
    value
    "/oidc/device"))
