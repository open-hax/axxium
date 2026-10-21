(ns axxium.extern.identity-routes-test
  "Regression fixtures exercise real route and transaction adapters without a database."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.extern.atproto-oauth :as oauth]
            [axxium.extern.http :as http]
            [axxium.routes.agents :as agents]
            [axxium.routes.atproto-oauth :as atproto]
            [cljs.test :refer [deftest is]]
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
                                 :else (js/Promise.resolve (result [{:id "fresh-actor"}]))))]
                   #js {:query query :release #(swap! released inc)}))
        direct (client)]
    {:pool #js {:query (.-query direct) :connect #(js/Promise.resolve (client))}
     :committed committed :commands commands :released released :binding binding}))

(deftest session-cookie-duration-matches-expiry-in-seconds
  (let [options (atom nil)]
    (with-redefs [cfg/get-in-config (fn [path] (when (= path [:jwt/expiry-hours]) 168))
                  http/set-session-cookie! (fn [_ _ _ value] (reset! options value))]
      (session/set-session-cookie nil "session-token")
      (is (= 604800 (:max-age @options))))))

(deftest ^:async unauthenticated-agent-operations-use-401
  (let [response (atom nil)]
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve nil))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (doseq [[_ handler] (agent-handlers)]
        (await (handler {} nil))
        (is (= 401 (:status @response)))))))

(deftest ^:async authenticated-nonadministrators-use-403
  (let [response (atom nil)]
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id "human" :auth/roles []}))
                  db/query-one-sql (fn [_] (js/Promise.resolve {:kind "human"}))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (doseq [[_ handler] (agent-handlers)]
        (await (handler {} nil))
        (is (= 403 (:status @response)))))))

(deftest ^:async agent-insert-failure-leaves-no-orphan-entity
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

(deftest ^:async credential-database-failures-have-controlled-responses
  (let [response (atom nil)
        lookups (atom 0)]
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id "admin" :auth/roles [:axxium/system-admin]}))
                  db/query-one-sql (fn [_] (if (= 1 (swap! lookups inc))
                                            (js/Promise.resolve {:kind "human"})
                                            (js/Promise.reject (js/Error. "database unavailable"))))
                  http/param (fn [_ _] "fixture-id")
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (doseq [path [[:get "/api/actors/:id/credentials"] [:delete "/api/actors/:id/credentials/:credentialId"]]]
        (reset! lookups 0)
        (reset! response nil)
        (try (await ((get (agent-handlers) path) {} nil)) (catch :default _ nil))
        (is (= 500 (:status @response)))))))

(defn- start-callbacks [callback]
  #js [(callback {} nil) (callback {} nil)])

(defn- ^:async run-callbacks! [fixture concurrent?]
  (let [responses (atom [])
        actors (atom [])
        callback (callback-handler)]
    (with-redefs [db/pool (delay (:pool fixture))
                  http/cookie (fn [_ _] "fixture-state")
                  http/clear-state-cookie! (fn ([_ _] nil) ([_ _ _] nil))
                  http/request-url (fn [_] "https://fixture.test/callback")
                  oauth/callback! (fn [_] (js/Promise.resolve {:did "did:plc:abcdefghijklmnopqrstuvwx" :state "fixture-state" :session nil}))
                  oauth/revoke! (fn [_] (js/Promise.resolve nil))
                  session/create-session! (fn [actor] (swap! actors conj (:id actor)) (js/Promise.resolve {:token "fixture-token"}))
                  session/set-session-cookie (fn [& _] nil)
                  http/redirect! (fn [_ path] (swap! responses conj {:redirect path}))
                  http/send! (fn [_ status body] (swap! responses conj {:status status :body body}))]
      (if concurrent?
        (await (js/Promise.all (start-callbacks callback)))
        (await (callback {} nil)))
      {:responses @responses :actors @actors})))

(deftest ^:async conflicting-first-login-rolls-back-and-returns-existing-actor
  (let [fixture (database-fixture {:conflict? true})
        outcome (await (run-callbacks! fixture false))]
    (is (= ["winning-actor"] (:actors outcome)))
    (is (= [{:redirect "/portal/index.html"}] (:responses outcome)))
    (is (empty? @(:committed fixture)))
    (is (some #{"ROLLBACK"} @(:commands fixture)))
    (is (= 1 @(:released fixture)))))

(deftest ^:async concurrent-first-logins-converge-without-orphans
  (let [fixture (database-fixture {:concurrent? true})
        outcome (await (run-callbacks! fixture true))]
    (is (= 2 (count (:actors outcome))))
    (is (= 1 (count (set (:actors outcome)))))
    (is (= 3 (count @(:committed fixture))))
    (is (= 2 (count (filter :redirect (:responses outcome)))))
    (is (= 2 @(:released fixture)))))
