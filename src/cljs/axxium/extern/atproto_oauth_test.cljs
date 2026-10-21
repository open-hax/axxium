(ns axxium.extern.atproto-oauth-test
  (:require [axxium.config :as cfg]
            [axxium.extern.atproto-oauth :as oauth]
            [cljs.test :refer [async deftest is]]
            ["@atproto/oauth-client-node" :refer [NodeOAuthClient]]))

(defn- fixture-saved-state
  "Minimal NodeSavedState-shaped value for store tests."
  [label]
  #js {:iss "https://bsky.social"
       :verifier (str "verifier-" label)
       :authMethod #js {:method "none"}
       :dpopJwk #js {:kty "EC" :crv "P-256" :x "x" :y "y" :d "d"}})


(deftest abandoned-authorizations-are-bounded
  (let [store (oauth/new-oauth-store)]
    (doseq [n (range 1025)]
      (.set store (str "state-" n) n))
    (is (nil? (.get store "state-0")))
    (is (= 1024 (.get store "state-1024")))))

(deftest live-authorization-state-is-never-evicted
  (let [now (atom 0)
        store (oauth/new-oauth-state-store {:ttl-ms 1000 :max 3 :now (fn [] @now)})
        entry fixture-saved-state
        refusal (fn [k] (try (.set store k (entry "new")) nil (catch :default e e)))]
    (doseq [n (range 3)] (.set store (str "state-" n) (entry n)))
    (let [error (refusal "state-3")]
      (is (= 503 (:status (ex-data error))) "a start at capacity is refused, not admitted by eviction")
      (is (oauth/state-store-full? error)))
    (is (some? (.get store "state-0")) "an earlier user's live state survives later starts")
    (.set store "state-1" (entry "replaced"))
    (is (some? (.get store "state-1")) "rewriting a live key needs no new capacity")
    (.del store "state-2")
    (.set store "state-3" (entry 3))
    (is (some? (.get store "state-3")) "a consumed state frees capacity")
    (reset! now 1000)
    (is (nil? (.get store "state-0")) "state expires at its TTL")
    (.set store "state-4" (entry 4))
    (.set store "state-5" (entry 5))
    (is (some? (.get store "state-5")) "expired entries free capacity")
    (is (nil? (refusal "state-6")) "freed capacity admits a new start")))

(deftest authorization-state-ttl-matches-browser-cookie
  ;; Cookie maxAge is 300s; store TTL adds a 60s authorize/PAR margin so state
  ;; cannot expire while the cookie is still valid after a slow authorize!.
  (is (= 360000 oauth/auth-state-ttl-ms))
  (let [now (atom 0)
        store (oauth/new-oauth-state-store {:max 2 :now (fn [] @now)})
        value (fixture-saved-state "early")]
    (.set store "early" value)
    (reset! now 359999)
    (is (identical? value (.get store "early")) "state remains live through cookie lifetime plus margin")
    (reset! now 360000)
    (is (nil? (.get store "early")) "state expires after the authorize margin")))

(deftest oauth-state-store-rejects-contract-violations
  (let [store (oauth/new-oauth-state-store {:max 2})
        refuse (fn [k v] (try (.set store k v) :admitted (catch :default e e)))
        ok (fixture-saved-state "ok")]
    (is (ex-data (refuse "" ok)) "empty key is refused")
    (is (ex-data (refuse "k" "primitive")) "primitive string value is refused")
    (is (ex-data (refuse "k" #js {})) "empty object missing NodeSavedState fields is refused")
    (is (ex-data (refuse "k" #js {:iss "https://bsky.social"})) "partial saved-state is refused")
    (.set store "k" ok)
    (is (identical? ok (.get store "k")))))

(deftest oauth-state-store-requires-complete-rsa-private-jwk
  ;; Codex PRRT_kwDOU4Vg086s2hg3: the RSA projection must keep and require the
  ;; CRT members (p q dp dq qi) that JoseKey.fromJWK needs at callback time.
  (let [store (oauth/new-oauth-state-store {:max 4})
        rsa (fn [jwk] #js {:iss "https://bsky.social"
                           :verifier "v"
                           :authMethod #js {:method "none"}
                           :dpopJwk (clj->js jwk)})
        full {:kty "RSA" :n "n" :e "AQAB" :d "d"
              :p "p" :q "q" :dp "dp" :dq "dq" :qi "qi"}
        refuse (fn [k v] (try (.set store k v) :admitted (catch :default e e)))]
    (is (= :admitted (refuse "rsa-full" (rsa full))) "a complete RSA private JWK is admitted")
    (is (ex-data (refuse "rsa-ned" (rsa (select-keys full [:kty :n :e :d]))))
        "n/e/d without CRT members is refused at write time")
    (doseq [k [:p :q :dp :dq :qi]]
      (is (ex-data (refuse (str "rsa-no-" (name k)) (rsa (dissoc full k))))
          (str "RSA private JWK without " (name k) " is refused")))))

(deftest configured-client-metadata-is-accepted-by-the-sdk
  (let [metadata (oauth/client-metadata)]
    (is (contains? #{"native" "web"} (:application_type metadata)))
    (is (seq (:redirect_uris metadata)))))

(defn- ^:async oauth-session-operations-serialize-by-key* []
  (let [release-first (atom nil)
        second-started (atom false)
        first-result (oauth/with-local-oauth-lock
                      "same-session"
                      (fn [] (js/Promise. (fn [resolve _] (reset! release-first resolve)))))
        second-result (oauth/with-local-oauth-lock
                       "same-session"
                       (fn []
                         (reset! second-started true)
                         (js/Promise.resolve :second)))]
    (await (js/Promise.resolve))
    (is (some? @release-first))
    (is (false? @second-started))
    (@release-first :first)
    (is (= :first (await first-result)))
    (is (= :second (await second-result)))
    (is @second-started)))

(defn- ^:async failed-session-operation-releases-only-its-own-position* []
  (let [reject-first (atom nil)
        release-second (atom nil)
        third-started (atom false)
        first-result (oauth/with-local-oauth-lock
                      "failing-session"
                      (fn [] (js/Promise. (fn [_ reject] (reset! reject-first reject)))))
        second-result (oauth/with-local-oauth-lock
                       "failing-session"
                       (fn [] (js/Promise. (fn [resolve _] (reset! release-second resolve)))))]
    (is (= :independent
           (await (oauth/with-local-oauth-lock
                   "another-session" (fn [] :independent)))))
    (is (some? @reject-first))
    (is (nil? @release-second))
    (@reject-first (js/Error. "session failed"))
    (is (= "session failed"
           (try (await first-result) (catch :default error (.-message error)))))
    (await (js/Promise.resolve))
    (is (some? @release-second))
    (let [third-result (oauth/with-local-oauth-lock
                        "failing-session"
                        (fn [] (reset! third-started true) :third))]
      (await (js/Promise.resolve))
      (is (false? @third-started))
      (@release-second :second)
      (is (= :second (await second-result)))
      (is (= :third (await third-result)))
      (is (= :after-cleanup
             (await (oauth/with-local-oauth-lock
                     "failing-session" (fn [] :after-cleanup))))))))

;; A bare `^:async` deftest drops a rejected promise: assertions after the rejection
;; never run and the suite still exits 0. Wrap each in `async`/`done` and report throws.
(deftest oauth-session-operations-serialize-by-key
  (async done (-> (oauth-session-operations-serialize-by-key*) (.catch (fn [e] (is false (str "oauth-session-operations-serialize-by-key rejected: " (pr-str e))))) (.finally done))))

(deftest failed-session-operation-releases-only-its-own-position
  (async done (-> (failed-session-operation-releases-only-its-own-position*) (.catch (fn [e] (is false (str "failed-session-operation-releases-only-its-own-position rejected: " (pr-str e))))) (.finally done))))

(deftest oauth-state-store-validates-keys-on-every-lookup
  ;; Codex PRRT_kwDOU4Vg086ujcP2 (riatzukiza/axxium#10): an absent malformed key
  ;; must be a contract violation, not ordinary missing state.
  (let [store (oauth/new-oauth-state-store {:max 2})
        lookup (fn [k] (try (.get store k) :missing-state (catch :default e e)))]
    (doseq [k ["" nil 42 (apply str (repeat 2049 "k"))]]
      (is (= :axxium.law.atproto/OAuthStateStoreKey (:contract (ex-data (lookup k))))
          (str "absent malformed key " (pr-str (if (string? k) (count k) k)) " is refused")))
    (is (nil? (.get store "absent-but-valid")) "an absent valid key is ordinary missing state")))

(deftest atproto-start-limiter-bounds-each-client
  (let [now (atom 0)
        admit? (oauth/new-start-limiter {:window-ms 1000 :max 2 :max-clients 2 :now (fn [] @now)})]
    (is (true? (admit? "1.1.1.1")))
    (is (true? (admit? "1.1.1.1")))
    (is (false? (admit? "1.1.1.1")) "a client over its per-window share is refused")
    (is (true? (admit? "2.2.2.2")) "other clients keep their own share")
    (is (false? (admit? "3.3.3.3")) "tracked clients are bounded; a new client at capacity fails closed")
    (reset! now 1000)
    (is (true? (admit? "1.1.1.1")) "the window slides")
    (is (true? (admit? "3.3.3.3")) "expired clients free tracking capacity")))

(deftest trust-proxy-option-keys-the-start-limiter-by-real-client
  ;; Behind a reverse proxy, req.ip is the proxy unless Fastify trusts it, which
  ;; would put every client in one start-limiter bucket.
  (is (nil? (cfg/trust-proxy-option nil)) "unset keeps Fastify's default (no proxy trust)")
  (is (nil? (cfg/trust-proxy-option "")))
  (is (= :axxium.config/trust-proxy-true
         (:axxium/reason (ex-data (try (cfg/trust-proxy-option "true") (catch :default e e)))))
      "CodeRabbit u4iC7: bare true lets direct clients pick req.ip, so it is refused")
  (is (false? (cfg/trust-proxy-option "false")) "CodeRabbit u3S5A: false is boolean, not an address")
  (is (false? (cfg/trust-proxy-option "FALSE")))
  (is (= :axxium.config/trust-proxy-hop-count
         (:axxium/reason (ex-data (try (cfg/trust-proxy-option "1") (catch :default e e)))))
      "CodeRabbit u3S4y / Codex u3Hib: Fastify 5 trusts nothing for a hop count, so it is refused")
  (is (= "127.0.0.1,10.0.0.0/8" (cfg/trust-proxy-option "127.0.0.1,10.0.0.0/8")) "trusted addresses"))

(defn- ^:async sdk-authorize-state*
  "Drive the pinned SDK's real authorize! up to stateStore.set (no network) and
   return what our store admitted for that state."
  []
  (let [store (oauth/new-oauth-state-store {:max 4})
        written (atom nil)
        stop (js/Error. "stop-after-state-set")
        recording #js {:get (fn [k] (.get store k))
                       :set (fn [k v] (.set store k v) (reset! written k) (throw stop))
                       :del (fn [k] (.del store k))}
        sdk (NodeOAuthClient. #js {:clientMetadata (clj->js (oauth/client-metadata))
                                   :stateStore recording
                                   :sessionStore (oauth/new-oauth-store)})]
    (set! (.-oauthResolver sdk)
          #js {:resolve (fn [_ _]
                          (js/Promise.resolve
                           #js {:metadata #js {:issuer "https://bsky.social"
                                               :token_endpoint_auth_methods_supported #js ["none" "private_key_jwt"]
                                               :dpop_signing_alg_values_supported #js ["ES256"]}}))})
    (let [outcome (try (await (.authorize sdk "https://bsky.social")) :no-stop
                       (catch :default e (if (identical? e stop) :admitted e)))]
      {:outcome outcome :saved (when @written (.get store @written))})))

(deftest oauth-state-store-admits-the-pinned-sdk-state
  ;; Codex PRRT_kwDOU4Vg086ujn7r (riatzukiza/axxium#10): validate what the pinned
  ;; SDK actually passes to stateStore.set, not only fabricated fixtures.
  (async done
         (-> (sdk-authorize-state*)
             (.then (fn [{:keys [outcome saved]}]
                      (is (= :admitted outcome) "the SDK's own NodeSavedState passes the contract")
                      (is (= "none" (some-> saved .-authMethod .-method)))))
             (.catch (fn [e] (is false (str "sdk authorize rejected: " (pr-str e)))))
             (.finally done))))

(deftest oauth-state-store-accepts-a-string-auth-method
  (let [store (oauth/new-oauth-state-store {:max 4})
        state (fn [auth-method]
                #js {:iss "https://bsky.social"
                     :verifier "verifier"
                     :authMethod auth-method
                     :dpopJwk #js {:kty "EC" :crv "P-256" :x "x" :y "y" :d "d"}})
        refuse (fn [k v] (try (.set store k v) nil (catch :default e (ex-data e))))]
    (.set store "bare" (state "none"))
    (is (= "none" (some-> (.get store "bare") .-authMethod .-method))
        "a bare string authMethod is admitted and handed back in the SDK's object form")
    (is (= :axxium.law.atproto/OAuthSavedState
           (:contract (refuse "jwt" (state "private_key_jwt"))))
        "a bare private_key_jwt string has no kid and is refused")
    (is (= :axxium.law.atproto/OAuthSavedState
           (:contract (refuse "other" (state "client_secret_basic"))))
        "an unknown auth method string is refused")))

(deftest start-limiter-keys-ipv6-clients-by-network-prefix
  ;; CodeRabbit PRRT_kwDOU4Vg086ukXYo (riatzukiza/axxium#10): one client can hold
  ;; many IPv6 addresses, so the limiter keys IPv6 by /64 and unwraps IPv4-mapped.
  (is (= "203.0.113.7" (oauth/start-limiter-key "203.0.113.7")))
  (is (= "203.0.113.7" (oauth/start-limiter-key "::ffff:203.0.113.7")))
  (is (= "203.0.113.7" (oauth/start-limiter-key "::FFFF:203.0.113.7")))
  (is (= "203.0.113.7" (oauth/start-limiter-key "::ffff:cb00:7107")) "hex IPv4-mapped form")
  (is (= "2001:db8:1:2::/64" (oauth/start-limiter-key "2001:db8:1:2:aaaa:bbbb:cccc:dddd")))
  (is (= "2001:db8:1:2::/64" (oauth/start-limiter-key "2001:0DB8:0001:0002::9")))
  (is (not= (oauth/start-limiter-key "2001:db8:1:2::1") (oauth/start-limiter-key "2001:db8:1:3::1")))
  (is (= "unknown" (oauth/start-limiter-key nil)))
  (let [admit? (oauth/new-start-limiter {:window-ms 1000 :max 2 :max-clients 8 :now (constantly 0)})]
    (is (true? (admit? "2001:db8:1:2::1")))
    (is (true? (admit? "2001:db8:1:2::2")))
    (is (false? (admit? "2001:db8:1:2::3")) "rotating addresses inside one /64 share one window")
    (is (true? (admit? "2001:db8:1:3::1")) "another /64 keeps its own share")))
