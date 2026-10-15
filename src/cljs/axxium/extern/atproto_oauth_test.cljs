(ns axxium.extern.atproto-oauth-test
  (:require [axxium.extern.atproto-oauth :as oauth]
            [cljs.test :refer [async deftest is]]))

(deftest abandoned-authorizations-are-bounded
  (let [store (oauth/new-oauth-store)]
    (doseq [n (range 1025)]
      (.set store (str "state-" n) n))
    (is (nil? (.get store "state-0")))
    (is (= 1024 (.get store "state-1024")))))

(deftest live-authorization-state-is-never-evicted
  (let [now (atom 0)
        store (oauth/new-oauth-state-store {:ttl-ms 1000 :max 3 :now (fn [] @now)})
        entry (fn [n] {:n n})
        refusal (fn [k] (try (.set store k (entry :new)) nil (catch :default e e)))]
    (doseq [n (range 3)] (.set store (str "state-" n) (entry n)))
    (let [error (refusal "state-3")]
      (is (= 503 (:status (ex-data error))) "a start at capacity is refused, not admitted by eviction")
      (is (oauth/state-store-full? error)))
    (is (= (entry 0) (.get store "state-0")) "an earlier user's live state survives later starts")
    (.set store "state-1" (entry :replaced))
    (is (= (entry :replaced) (.get store "state-1")) "rewriting a live key needs no new capacity")
    (.del store "state-2")
    (.set store "state-3" (entry 3))
    (is (= (entry 3) (.get store "state-3")) "a consumed state frees capacity")
    (reset! now 1000)
    (is (nil? (.get store "state-0")) "state expires at its TTL")
    (.set store "state-4" (entry 4))
    (.set store "state-5" (entry 5))
    (is (= (entry 5) (.get store "state-5")) "expired entries free capacity")
    (is (nil? (refusal "state-6")) "freed capacity admits a new start")))

(deftest authorization-state-ttl-matches-browser-cookie
  ;; Cookie maxAge is 300s; store TTL adds a 60s authorize/PAR margin so state
  ;; cannot expire while the cookie is still valid after a slow authorize!.
  (is (= 360000 oauth/auth-state-ttl-ms))
  (let [now (atom 0)
        store (oauth/new-oauth-state-store {:max 2 :now (fn [] @now)})
        value {:v true}]
    (.set store "early" value)
    (reset! now 359999)
    (is (= value (.get store "early")) "state remains live through cookie lifetime plus margin")
    (reset! now 360000)
    (is (nil? (.get store "early")) "state expires after the authorize margin")))

(deftest oauth-state-store-rejects-contract-violations
  (let [store (oauth/new-oauth-state-store {:max 2})
        refuse (fn [k v] (try (.set store k v) :admitted (catch :default e e)))]
    (is (ex-data (refuse "" {:ok true})) "empty key is refused")
    (is (ex-data (refuse "k" "primitive")) "primitive string value is refused")
    (is (ex-data (refuse "k" 42)) "primitive number value is refused")
    (.set store "k" {:ok true})
    (is (= {:ok true} (.get store "k")))))

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
