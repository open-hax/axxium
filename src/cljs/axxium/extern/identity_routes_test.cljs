(ns axxium.extern.identity-routes-test
  "Regression fixtures exercise real route and transaction adapters without a database."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.extern.atproto-oauth :as oauth]
            [axxium.extern.http :as http]
            [axxium.routes.agents :as agents]
            [axxium.routes.atproto-oauth :as atproto]
            [cljs.test :refer [async deftest is]]
            [clojure.string :as str]))

(defn- agent-handlers []
  (let [handlers (atom {})
        register (fn [method] (fn [_ path handler] (swap! handlers assoc [method path] handler)))]
    (with-redefs [http/post! (register :post) http/get! (register :get) http/delete! (register :delete)]
      (agents/register-agent-routes! nil))
    @handlers))

(defn- callback-handler []
  (let [routes (atom {})]
    (with-redefs [http/get! (fn [_ path handler] (swap! routes assoc path handler))]
      (atproto/register-atproto-oauth-routes! nil))
    (get @routes "/api/auth/atproto/callback")))

(defn- result [rows]
  #js {:rows (clj->js rows) :rowCount (count rows)})

(defn- database-fixture [{:keys [fail-actor? conflict? concurrent?]}]
  (let [committed (atom [])
        commands (atom [])
        released (atom 0)
        binding (atom (when conflict? {:id "winning-actor"}))
        lookups (atom 0)
        pending-lookups (atom [])
        client (fn []
                 (let [staged (atom nil)
                       query (fn [sql params]
                               (swap! commands conj sql)
                               (cond
                                 (= sql "BEGIN") (do (reset! staged []) (js/Promise.resolve (result [])))
                                 (= sql "ROLLBACK") (do (reset! staged nil) (js/Promise.resolve (result [])))
                                 (= sql "COMMIT") (do (swap! committed into @staged) (reset! staged nil)
                                                      (js/Promise.resolve (result [])))
                                 (str/includes? sql "JOIN provider_bindings")
                                 (let [n (swap! lookups inc)]
                                   (if (and concurrent? (<= n 2))
                                     (js/Promise.
                                      (fn [resolve _]
                                        (swap! pending-lookups conj resolve)
                                        (when (= 2 (count @pending-lookups))
                                          (doseq [complete @pending-lookups] (complete (result []))))))
                                     (js/Promise.resolve
                                      (result (if (and (> n 1) @binding) [@binding] [])))))
                                 (str/starts-with? sql "INSERT INTO")
                                 (cond
                                   (and fail-actor? (str/includes? sql "INSERT INTO actors"))
                                   (js/Promise.reject (js/Error. "actor insert failed"))
                                   (and (str/includes? sql "INSERT INTO provider_bindings") @binding)
                                   (js/Promise.reject
                                    (doto (js/Error. "provider binding conflict")
                                      (aset "code" "23505")
                                      (aset "constraint" "idx_provider_bindings_subject")))
                                   :else
                                   (do
                                     (when (str/includes? sql "INSERT INTO provider_bindings")
                                       (reset! binding {:id (aget params 2)}))
                                     (swap! (if (some? @staged) staged committed) conj sql)
                                     (js/Promise.resolve (result []))))
                                 :else (js/Promise.resolve (result [{:id (aget params 0)}]))))]
                   #js {:query query :release (fn [_discard?] (swap! released inc))}))
        direct (client)]
    {:pool #js {:query (.-query direct) :connect #(js/Promise.resolve (client))}
     :committed committed :commands commands :released released :binding binding}))

(deftest session-cookie-duration-matches-expiry-in-seconds
  (let [options (atom nil)]
    (with-redefs [cfg/get-in-config (fn [path] (when (= path [:jwt/expiry-hours]) 168))
                  http/set-session-cookie! (fn [_ _ _ value] (reset! options value))]
      (session/set-session-cookie nil "session-token")
      (is (= 604800 (:max-age @options))))))

(defn- ^:async unauthenticated-agent-operations-use-401* []
  (let [response (atom nil)]
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve nil))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (doseq [[_ handler] (agent-handlers)]
        (reset! response nil)
        (await (handler {} nil))
        (is (= 401 (:status @response)))))))

(defn- ^:async authenticated-nonadministrators-use-403* []
  (let [response (atom nil)]
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id "human" :auth/roles []}))
                  db/query-one-sql (fn [_] (js/Promise.resolve {:kind "human"}))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (doseq [[_ handler] (agent-handlers)]
        (reset! response nil)
        (await (handler {} nil))
        (is (= 403 (:status @response)))))))

(defn- ^:async agent-insert-failure-leaves-no-orphan-entity* []
  (let [{:keys [pool committed commands released]} (database-fixture {:fail-actor? true})
        response (atom nil)]
    (with-redefs [db/pool (delay pool)
                  session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id "admin" :auth/roles [:axxium/system-admin]}))
                  db/query-one-sql (fn [_] (js/Promise.resolve {:kind "human"}))
                  http/body (fn [_] {:display-name "Fixture agent"})
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (await ((get (agent-handlers) [:post "/api/actors/agents"]) {} nil))
      (is (= 500 (:status @response)))
      (is (empty? @committed))
      (is (= "ROLLBACK" (last @commands)))
      (is (= 1 @released)))))

(defn- ^:async credential-database-failures-have-controlled-responses* []
  (let [response (atom nil)
        lookups (atom 0)]
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id "admin" :auth/roles [:axxium/system-admin]}))
                  db/query-one-sql (fn [_] (if (= 1 (swap! lookups inc))
                                            (js/Promise.resolve {:kind "human"})
                                            (js/Promise.reject (js/Error. "database unavailable"))))
                  http/param (fn [_ name] (if (= name "credentialId") "11111111-1111-4111-8111-111111111111" "fixture-id"))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (doseq [path [[:get "/api/actors/:id/credentials"] [:delete "/api/actors/:id/credentials/:credentialId"]]]
        (reset! lookups 0)
        (reset! response nil)
        (try (await ((get (agent-handlers) path) {} nil)) (catch :default _ nil))
        (is (= 500 (:status @response)))))))

(defn- start-callbacks [callback]
  #js [(callback {} nil) (callback {} nil)])

(defn- ^:async run-callbacks!
  ([fixture concurrent?] (await (run-callbacks! fixture concurrent? {})))
  ([fixture concurrent? {:keys [revoke! cookie]
                          :or {revoke! (fn [_] (js/Promise.resolve nil)) cookie "fixture-state"}}]
  (let [responses (atom [])
        actors (atom [])
        events (atom [])
        callback (callback-handler)]
    (with-redefs [db/pool (delay (:pool fixture))
                  http/cookie (fn [_ _] cookie)
                  http/clear-state-cookie! (fn ([_ _] nil) ([_ _ _] nil))
                  http/request-url (fn [_] "https://fixture.test/callback")
                  oauth/callback! (fn [_] (js/Promise.resolve {:did "did:plc:abcdefghijklmnopqrstuvwx" :state "fixture-state" :session nil}))
                  oauth/revoke! (fn [s] (swap! events conj :revoke) (revoke! s))
                  session/create-session! (fn [actor] (swap! events conj :session) (swap! actors conj (:id actor)) (js/Promise.resolve {:token "fixture-token"}))
                  session/set-session-cookie (fn [& _] nil)
                  http/redirect! (fn [_ path] (swap! responses conj {:redirect path}))
                  http/send! (fn [_ status body] (swap! responses conj {:status status :body body}))]
      (if concurrent?
        (await (js/Promise.all (start-callbacks callback)))
        (await (callback {} nil)))
      {:responses @responses :actors @actors :events @events}))))

(defn- ^:async revocation-failure-does-not-fail-verified-sign-in* []
  (let [fixture (database-fixture {:conflict? true})
        outcome (await (run-callbacks! fixture false
                                       {:revoke! (fn [_] (js/Promise.reject (js/Error. "revocation endpoint unavailable")))}))]
    (is (= [{:redirect "/portal/index.html"}] (:responses outcome))
        "a rejected token revocation must not turn a verified sign-in into 502")
    (is (= ["winning-actor"] (:actors outcome)))
    (is (= [:session :revoke] (:events outcome))
        "identity is bound and the session issued before provider tokens are revoked")))

(defn- ^:async mismatched-state-still-revokes-provider-tokens* []
  (let [fixture (database-fixture {})
        revoked (atom 0)
        outcome (await (run-callbacks! fixture false
                                       {:cookie "other-state"
                                        :revoke! (fn [_] (swap! revoked inc) (js/Promise.resolve nil))}))]
    (is (= [{:status 400 :body {:error "AT Protocol identity or browser state mismatch"}}] (:responses outcome)))
    (is (= 1 @revoked) "provider tokens are revoked even when the callback is rejected")
    (is (empty? (:actors outcome)))))

(defn- ^:async conflicting-first-login-rolls-back-and-returns-existing-actor* []
  (let [fixture (database-fixture {:conflict? true})
        outcome (await (run-callbacks! fixture false))]
    (is (= ["winning-actor"] (:actors outcome)))
    (is (= [{:redirect "/portal/index.html"}] (:responses outcome)))
    (is (empty? @(:committed fixture)))
    (is (some #{"ROLLBACK"} @(:commands fixture)))
    (is (= 1 @(:released fixture)))))

(defn- ^:async concurrent-first-logins-converge-without-orphans* []
  (let [fixture (database-fixture {:concurrent? true})
        outcome (await (run-callbacks! fixture true))]
    (is (= 2 (count (:actors outcome))))
    (is (= 1 (count (set (:actors outcome)))))
    (is (= 3 (count @(:committed fixture))))
    (is (= 2 (count (filter :redirect (:responses outcome)))))
    (is (= 2 @(:released fixture)))))

(defn- ^:async agent-success-commits-both-records* []
  (let [{:keys [pool committed commands released]} (database-fixture {})
        response (atom nil)]
    (with-redefs [db/pool (delay pool)
                  session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id "admin" :auth/roles [:axxium/system-admin]}))
                  db/query-one-sql (fn [_] (js/Promise.resolve {:kind "human"}))
                  http/body (fn [_] {:display-name " Fixture agent "})
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (await ((get (agent-handlers) [:post "/api/actors/agents"]) {} nil))
      (is (= 201 (:status @response)))
      (is (= "Fixture agent" (get-in @response [:body :actor :display_name])))
      (is (= 2 (count @committed)))
      (is (= "COMMIT" (last @commands)))
      (is (= 1 @released)))))

(defn- ^:async credential-success-and-not-found-responses-are-preserved* []
  (let [response (atom nil)
        calls (atom 0)
        revoked (atom [])]
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id "admin" :auth/roles [:axxium/system-admin]}))
                  db/query-one-sql (fn [_] (js/Promise.resolve (case (mod (swap! calls inc) 3)
                                                              1 {:kind "human"}
                                                              2 {:id "agent"}
                                                              {:kind "agent"})))
                  db/query-all-sql (fn [_] (js/Promise.resolve [{:id "credential"}]))
                  db/query-sql (fn [_] (js/Promise.resolve @revoked))
                  http/param (fn [_ name] (if (= name "credentialId") "11111111-1111-4111-8111-111111111111" "fixture-id"))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (let [handlers (agent-handlers)]
        (await ((get handlers [:get "/api/actors/:id/credentials"]) {} nil))
        (is (= {:status 200 :body {:credentials [{:id "credential"}]}} @response))
        (await ((get handlers [:delete "/api/actors/:id/credentials/:credentialId"]) {} nil))
        (is (= {:status 404 :body {:error "Credential not found"}} @response))
        (reset! revoked [{:id "credential"}])
        (await ((get handlers [:delete "/api/actors/:id/credentials/:credentialId"]) {} nil))
        (is (= {:status 200 :body {:ok true}} @response))))))

(defn- ^:async unrelated-first-login-errors-roll-back-without-recovery* []
  (let [fixture (database-fixture {:fail-actor? true})
        outcome (await (run-callbacks! fixture false))]
    (is (empty? (:actors outcome)))
    (is (= [{:status 502 :body {:error "AT Protocol sign-in failed"}}] (:responses outcome)))
    (is (empty? @(:committed fixture)))
    (is (= 1 @(:released fixture)))))

(defn- ^:async run-link-callback!
  "Drive a link callback whose DID binding loses a concurrent unique-index race
   to `winner` (the binding row that committed first)."
  [winner]
  (let [responses (atom [])
        actors (atom [])
        lookups (atom 0)
        callback (callback-handler)
        state "link|actor-a|fixture-token"]
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id "actor-a"}))
                  db/q-select-entity-for-actor (fn [id] [:entity id])
                  db/q-select-actor-by-provider-subject (fn [_ subject] [:binding subject])
                  db/q-insert-provider-binding (fn [_ subject id] [:insert-binding subject id])
                  db/q-select-actor-by-id (fn [{:keys [id]}] [:actor id])
                  db/query-one-sql (fn [[tag id]]
                                     (js/Promise.resolve
                                      (case tag
                                        :entity {:id "entity-a" :kind "human"}
                                        ;; Both racers observed no binding before inserting.
                                        :binding (when (< 1 (swap! lookups inc)) {:id winner})
                                        :actor {:id id})))
                  db/query-sql (fn [[tag]]
                                 (if (= :insert-binding tag)
                                   (js/Promise.reject
                                    (doto (js/Error. "duplicate key value violates unique constraint")
                                      (aset "code" "23505")
                                      (aset "constraint" "idx_provider_bindings_subject")))
                                   (js/Promise.resolve [])))
                  http/cookie (fn [_ _] state)
                  http/clear-state-cookie! (fn ([_ _] nil) ([_ _ _] nil))
                  http/request-url (fn [_] "https://fixture.test/callback")
                  oauth/callback! (fn [_] (js/Promise.resolve {:did "did:plc:abcdefghijklmnopqrstuvwx" :state state :session nil}))
                  oauth/revoke! (fn [_] (js/Promise.resolve nil))
                  session/create-session! (fn [actor] (swap! actors conj (:id actor)) (js/Promise.resolve {:token "fixture-token"}))
                  session/set-session-cookie (fn [& _] nil)
                  http/redirect! (fn [_ path] (swap! responses conj {:redirect path}))
                  http/send! (fn [_ status body] (swap! responses conj {:status status :body body}))]
      (await (callback {} nil))
      {:responses @responses :actors @actors})))

(defn- ^:async concurrent-link-by-same-actor-succeeds* []
  (let [outcome (await (run-link-callback! "actor-a"))]
    (is (= [{:redirect "/portal/index.html"}] (:responses outcome))
        "losing the unique-index race to the same actor's own link is a success")
    (is (= ["actor-a"] (:actors outcome)))))

(defn- ^:async concurrent-link-by-other-actor-is-409* []
  (let [outcome (await (run-link-callback! "actor-b"))]
    (is (= [{:status 409 :body {:error "AT identity is already bound to another actor"}}] (:responses outcome))
        "losing the unique-index race to another actor is a 409, not a raw 502")
    (is (empty? (:actors outcome)))))

(defn- ^:async start-at-capacity-is-503* []
  (let [routes (atom {})
        response (atom nil)]
    (with-redefs [http/get! (fn [_ path handler] (swap! routes assoc path handler))
                  http/with-canonical-origin (fn [_ _ handler] handler)]
      (atproto/register-atproto-oauth-routes! nil))
    (with-redefs [http/query-param (fn [_ k] (when (= k "identity") "alice.example.com"))
                  oauth/authorize! (fn [_ _] (js/Promise.reject (oauth/state-store-full-error)))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (await ((get @routes "/api/auth/atproto/start") {} nil))
      (is (= 503 (:status @response))
          "a full authorization-state store refuses new starts with 503, never by evicting live state")
      (is (= {:error "Too many AT Protocol sign-ins in progress; try again shortly"} (:body @response))))))

;; A bare `^:async` deftest drops a rejected promise: assertions after the rejection
;; never run and the suite still exits 0. Wrap each in `async`/`done` and report throws.
(deftest unauthenticated-agent-operations-use-401
  (async done (-> (unauthenticated-agent-operations-use-401*) (.catch (fn [e] (is false (str "unauthenticated-agent-operations-use-401 rejected: " (pr-str e))))) (.finally done))))

(deftest authenticated-nonadministrators-use-403
  (async done (-> (authenticated-nonadministrators-use-403*) (.catch (fn [e] (is false (str "authenticated-nonadministrators-use-403 rejected: " (pr-str e))))) (.finally done))))

(deftest agent-insert-failure-leaves-no-orphan-entity
  (async done (-> (agent-insert-failure-leaves-no-orphan-entity*) (.catch (fn [e] (is false (str "agent-insert-failure-leaves-no-orphan-entity rejected: " (pr-str e))))) (.finally done))))

(deftest credential-database-failures-have-controlled-responses
  (async done (-> (credential-database-failures-have-controlled-responses*) (.catch (fn [e] (is false (str "credential-database-failures-have-controlled-responses rejected: " (pr-str e))))) (.finally done))))

(deftest conflicting-first-login-rolls-back-and-returns-existing-actor
  (async done (-> (conflicting-first-login-rolls-back-and-returns-existing-actor*) (.catch (fn [e] (is false (str "conflicting-first-login-rolls-back-and-returns-existing-actor rejected: " (pr-str e))))) (.finally done))))

(deftest concurrent-first-logins-converge-without-orphans
  (async done (-> (concurrent-first-logins-converge-without-orphans*) (.catch (fn [e] (is false (str "concurrent-first-logins-converge-without-orphans rejected: " (pr-str e))))) (.finally done))))

(deftest agent-success-commits-both-records
  (async done (-> (agent-success-commits-both-records*) (.catch (fn [e] (is false (str "agent-success-commits-both-records rejected: " (pr-str e))))) (.finally done))))

(deftest credential-success-and-not-found-responses-are-preserved
  (async done (-> (credential-success-and-not-found-responses-are-preserved*) (.catch (fn [e] (is false (str "credential-success-and-not-found-responses-are-preserved rejected: " (pr-str e))))) (.finally done))))

(deftest unrelated-first-login-errors-roll-back-without-recovery
  (async done (-> (unrelated-first-login-errors-roll-back-without-recovery*) (.catch (fn [e] (is false (str "unrelated-first-login-errors-roll-back-without-recovery rejected: " (pr-str e))))) (.finally done))))

(deftest revocation-failure-does-not-fail-verified-sign-in
  (async done (-> (revocation-failure-does-not-fail-verified-sign-in*)
                  (.catch (fn [e] (is false (str "revocation-failure-does-not-fail-verified-sign-in rejected: " (pr-str e)))))
                  (.finally done))))

(deftest mismatched-state-still-revokes-provider-tokens
  (async done (-> (mismatched-state-still-revokes-provider-tokens*)
                  (.catch (fn [e] (is false (str "mismatched-state-still-revokes-provider-tokens rejected: " (pr-str e)))))
                  (.finally done))))

(deftest concurrent-link-by-same-actor-succeeds
  (async done (-> (concurrent-link-by-same-actor-succeeds*)
                  (.catch (fn [e] (is false (str "concurrent-link-by-same-actor-succeeds rejected: " (pr-str e)))))
                  (.finally done))))

(deftest concurrent-link-by-other-actor-is-409
  (async done (-> (concurrent-link-by-other-actor-is-409*)
                  (.catch (fn [e] (is false (str "concurrent-link-by-other-actor-is-409 rejected: " (pr-str e)))))
                  (.finally done))))

(deftest start-at-capacity-is-503
  (async done (-> (start-at-capacity-is-503*)
                  (.catch (fn [e] (is false (str "start-at-capacity-is-503 rejected: " (pr-str e)))))
                  (.finally done))))

(defn- ^:async start-is-rate-limited-per-client* []
  ;; CodeRabbit cr-comment:v1:807a96710a9886471dc27c5f: a public client cannot
  ;; consume the shared authorization-state capacity with repeated starts.
  (let [routes (atom {})
        responses (atom [])
        authorizations (atom 0)]
    (with-redefs [http/get! (fn [_ path handler] (swap! routes assoc path handler))
                  http/with-canonical-origin (fn [_ _ handler] handler)]
      (atproto/register-atproto-oauth-routes! nil))
    (with-redefs [oauth/start-limiter (delay (oauth/new-start-limiter {:window-ms 60000 :max 3}))
                  http/query-param (fn [_ k] (when (= k "identity") "alice.example.com"))
                  oauth/authorize! (fn [_ _] (swap! authorizations inc) (js/Promise.resolve "https://pds.example/authorize"))
                  http/set-state-cookie! (fn [& _] nil)
                  http/redirect! (fn [_ url] (swap! responses conj {:redirect url}))
                  http/send! (fn [_ status body] (swap! responses conj {:status status :body body}))]
      (doseq [_ (range 4)]
        (await ((get @routes "/api/auth/atproto/start") #js {:ip "203.0.113.7"} nil)))
      (await ((get @routes "/api/auth/atproto/start") #js {:ip "198.51.100.9"} nil))
      (is (= 4 @authorizations) "the refused start never reaches the SDK or the state store")
      (is (= {:status 429 :body {:error "Too many AT Protocol sign-in attempts; try again shortly"}}
             (nth @responses 3)))
      (is (= {:redirect "https://pds.example/authorize"} (last @responses))
          "another client is unaffected"))))

(deftest start-is-rate-limited-per-client
  (async done (-> (start-is-rate-limited-per-client*)
                  (.catch (fn [e] (is false (str "start-is-rate-limited-per-client rejected: " (pr-str e)))))
                  (.finally done))))
