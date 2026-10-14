(ns axxium.extern.promote30-findings-test
  "Regressions for the Codex/CodeRabbit findings on open-hax/axxium#30."
  (:require [axxium.auth.session :as session]
            [axxium.auth.token :as token]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.extern.bcrypt :as bcrypt]
            [axxium.extern.http :as http]
            [axxium.extern.jose :as jose]
            [axxium.routes.agents :as agents]
            [axxium.routes.auth :as auth]
            [cljs.test :refer [async deftest is]]
            [clojure.string :as str]))

(def ^:private fixture-credential-id "11111111-1111-4111-8111-111111111111")

(defn- routes [register!]
  (let [handlers (atom {})
        reg (fn [method] (fn [_ path handler] (swap! handlers assoc [method path] handler)))]
    (with-redefs [http/post! (reg :post) http/get! (reg :get) http/delete! (reg :delete)]
      (register! nil))
    @handlers))

(defn- result [rows]
  #js {:rows (clj->js rows) :rowCount (count rows)})

(defn- signup-db [{:keys [fail-actor? email-conflict?]}]
  (let [committed (atom []) commands (atom []) released (atom 0)
        client (fn []
                 (let [staged (atom nil)]
                   #js {:release (fn [_] (swap! released inc))
                        :query
                        (fn [sql params]
                          (swap! commands conj sql)
                          (cond
                            (= sql "BEGIN") (do (reset! staged []) (js/Promise.resolve (result [])))
                            (= sql "ROLLBACK") (do (reset! staged nil) (js/Promise.resolve (result [])))
                            (= sql "COMMIT") (do (swap! committed into @staged) (reset! staged nil)
                                                 (js/Promise.resolve (result [])))
                            (and (str/starts-with? sql "INSERT INTO actors") fail-actor?)
                            (js/Promise.reject (js/Error. "actor insert failed"))
                            (and (str/starts-with? sql "INSERT INTO actors") email-conflict?)
                            (js/Promise.reject
                             (doto (js/Error. "actors_email_unique")
                               (aset "code" "23505")
                               (aset "constraint" "idx_actors_email")))
                            (str/starts-with? sql "INSERT INTO")
                            (do (swap! (if (some? @staged) staged committed) conj sql)
                                (js/Promise.resolve (result [])))
                            :else (js/Promise.resolve (result [{:id (when params (aget params 0))}]))))}))
        direct (client)]
    {:pool #js {:query (.-query direct) :connect #(js/Promise.resolve (client))}
     :committed committed :commands commands :released released}))

(defn- ^:async malformed-credential-id-is-rejected-before-uuid-query*
  []
  (let [response (atom nil)
        revoke-queries (atom 0)
        lookups (atom 0)
        handler (get (routes agents/register-agent-routes!)
                     [:delete "/api/actors/:id/credentials/:credentialId"])]
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id "admin" :auth/roles [:axxium/system-admin]}))
                  db/query-one-sql (fn [_]
                                     (js/Promise.resolve
                                      (case (swap! lookups inc)
                                        1 {:kind "human"}
                                        2 {:id "agent"}
                                        {:kind "agent"})))
                  db/query-sql (fn [_] (swap! revoke-queries inc) (js/Promise.resolve [{:id "x"}]))
                  http/param (fn [_ name] (if (= name "credentialId") "not-a-uuid" "actor_agent"))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (await (handler {} nil))
      (is (= 400 (:status @response)))
      (is (zero? @revoke-queries)
          "malformed credential ids must not reach the uuid column"))))

(defn- ^:async jwt-verify-failures-return-nil-but-database-errors-propagate*
  []
  (with-redefs [token/verify-token (fn [_] (js/Promise.reject (js/Error. "bad jwt")))
                db/query-one-sql (fn [_] (js/Promise.resolve {:id "actor"}))]
    (is (nil? (await (session/verify-session "eyJhbGciOiJIUzI1NiJ9.payload.sig")))))
  (with-redefs [token/verify-token (fn [_] (js/Promise.resolve {:sub "actor"}))
                db/query-one-sql (fn [_] (js/Promise.reject (js/Error. "database unavailable")))]
    (is (= :propagated
           (try
             (await (session/verify-session "eyJhbGciOiJIUzI1NiJ9.payload.sig"))
             :returned
             (catch :default _ :propagated)))
        "JWT-path database errors must propagate like the axx_ branch"))
  (with-redefs [db/query-one-sql (fn [_] (js/Promise.reject (js/Error. "database unavailable")))]
    (is (= :propagated
           (try
             (await (session/verify-session "axx_secret"))
             :returned
             (catch :default _ :propagated)))
        "axx_ branch database errors must propagate")))

(defn- ^:async sign-actor-without-exp!
  [claims secret]
  (-> (new jose/SignJWT (clj->js claims))
      (.setProtectedHeader #js {"alg" "HS256" "typ" "JWT"})
      (.setIssuedAt)
      (.setIssuer "axxium")
      (.setAudience "axxium-api")
      (.sign (.encode (new (.-TextEncoder js/globalThis)) secret))
      (await)))

(defn- ^:async verify-actor-requires-hs256-and-exp*
  []
  (let [opts {:secret "fixture-secret" :issuer "axxium" :audience "axxium-api"}
        sealed (await (jose/sign-actor! {:sub "actor"} (assoc opts :expiry-hours 1)))
        no-exp (await (sign-actor-without-exp! {:sub "actor"} "fixture-secret"))]
    (is (= "actor" (:sub (await (jose/verify-actor! sealed opts)))))
    (is (= :rejected
           (try
             (await (jose/verify-actor! no-exp opts))
             :accepted
             (catch :default _ :rejected)))
        "actor tokens without exp must be rejected")))

(defn- ^:async signup-actor-failure-rolls-back-entity*
  []
  (let [fixture (signup-db {:fail-actor? true})
        response (atom nil)
        handler (get (routes auth/register-auth-routes!) [:post "/api/auth/signup"])]
    (with-redefs [db/pool (delay (:pool fixture))
                  db/query-one-sql (fn [_] (js/Promise.resolve nil))
                  bcrypt/hash (fn [_ _] (js/Promise.resolve "hash"))
                  cfg/get-in-config (fn [path] (case path
                                                 [:oauth/bootstrap-admin-email] ""
                                                 [:password/salt-rounds] 4
                                                 nil))
                  http/body (fn [_] {:email "a@b.test" :password "password1" :display-name "A"})
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (await (handler {} nil))
      (is (= 500 (:status @response)))
      (is (empty? @(:committed fixture)))
      (is (some #{"ROLLBACK"} @(:commands fixture)))
      (is (= 1 @(:released fixture))))))

(defn- ^:async signup-email-unique-conflict-returns-409*
  []
  (let [fixture (signup-db {:email-conflict? true})
        response (atom nil)
        handler (get (routes auth/register-auth-routes!) [:post "/api/auth/signup"])]
    (with-redefs [db/pool (delay (:pool fixture))
                  db/query-one-sql (fn [_] (js/Promise.resolve nil))
                  bcrypt/hash (fn [_ _] (js/Promise.resolve "hash"))
                  cfg/get-in-config (fn [path] (case path
                                                 [:oauth/bootstrap-admin-email] ""
                                                 [:password/salt-rounds] 4
                                                 nil))
                  http/body (fn [_] {:email "a@b.test" :password "password1" :display-name "A"})
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (await (handler {} nil))
      (is (= 409 (:status @response)))
      (is (= {:error "An account with this email already exists"} (:body @response)))
      (is (empty? @(:committed fixture)))
      (is (some #{"ROLLBACK"} @(:commands fixture))))))

(deftest malformed-credential-id-is-rejected-before-uuid-query
  (async done (-> (malformed-credential-id-is-rejected-before-uuid-query*)
                  (.catch (fn [e] (is false (str "malformed-credential-id-is-rejected-before-uuid-query rejected: " (pr-str e)))))
                  (.finally done))))

(deftest jwt-verify-failures-return-nil-but-database-errors-propagate
  (async done (-> (jwt-verify-failures-return-nil-but-database-errors-propagate*)
                  (.catch (fn [e] (is false (str "jwt-verify-failures-return-nil-but-database-errors-propagate rejected: " (pr-str e)))))
                  (.finally done))))

(deftest verify-actor-requires-hs256-and-exp
  (async done (-> (verify-actor-requires-hs256-and-exp*)
                  (.catch (fn [e] (is false (str "verify-actor-requires-hs256-and-exp rejected: " (pr-str e)))))
                  (.finally done))))

(deftest signup-actor-failure-rolls-back-entity
  (async done (-> (signup-actor-failure-rolls-back-entity*)
                  (.catch (fn [e] (is false (str "signup-actor-failure-rolls-back-entity rejected: " (pr-str e)))))
                  (.finally done))))

(deftest signup-email-unique-conflict-returns-409
  (async done (-> (signup-email-unique-conflict-returns-409*)
                  (.catch (fn [e] (is false (str "signup-email-unique-conflict-returns-409 rejected: " (pr-str e)))))
                  (.finally done))))
