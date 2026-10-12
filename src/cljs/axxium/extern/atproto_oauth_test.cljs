(ns axxium.extern.atproto-oauth-test
  (:require [axxium.extern.atproto-oauth :as oauth]
            [cljs.test :refer [async deftest is]]))

(deftest abandoned-authorizations-are-bounded
  (let [store (oauth/new-oauth-store)]
    (doseq [n (range 1025)]
      (.set store (str "state-" n) n))
    (is (nil? (.get store "state-0")))
    (is (= 1024 (.get store "state-1024")))))

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
