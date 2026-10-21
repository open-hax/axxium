(ns axxium.extern.codex-findings-test
  "Regressions for the Codex findings on open-hax/axxium#29."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.extern.google :as google]
            [axxium.extern.http :as http]
            [axxium.extern.jose :as jose]
            [axxium.routes.agents :as agents]
            [axxium.routes.atproto :as atproto]
            [axxium.routes.auth :as auth]
            [axxium.routes.google :as google-routes]
            [axxium.law.actor :as actor-law]
            [axxium.shape.agent :as agent-shape]
            [cljs.test :refer [async deftest is]]
            [clojure.string :as str]))

(defn- routes [register!]
  (let [handlers (atom {})
        reg (fn [method] (fn [_ path handler] (swap! handlers assoc [method path] handler)))]
    (with-redefs [http/post! (reg :post) http/get! (reg :get) http/delete! (reg :delete)
                  http/with-canonical-origin (fn [_ _ handler] handler)]
      (register! nil))
    @handlers))

(defn- result [rows] #js {:rows (clj->js rows) :rowCount (count rows)})

(defn- google-db [{:keys [fail-binding? conflict?]}]
  (let [committed (atom []) commands (atom []) released (atom 0)
        lookups (atom 0)
        client (fn []
                 (let [staged (atom nil)]
                   #js {:release (fn [_] (swap! released inc))
                        :query
                        (fn [sql params]
                          (swap! commands conj sql)
                          (cond
                            (= sql "BEGIN") (do (reset! staged []) (js/Promise.resolve (result [])))
                            (= sql "ROLLBACK") (do (reset! staged nil) (js/Promise.resolve (result [])))
                            (= sql "COMMIT") (do (swap! committed into @staged) (js/Promise.resolve (result [])))
                            (str/includes? sql "JOIN provider_bindings")
                            (js/Promise.resolve
                             (result (if (and conflict? (> (swap! lookups inc) 1)) [{:id "winner"}] [])))
                            (and (str/starts-with? sql "INSERT INTO provider_bindings")
                                 (or fail-binding? conflict?))
                            (js/Promise.reject
                             (if conflict?
                               (doto (js/Error. "conflict") (aset "code" "23505")
                                     (aset "constraint" "idx_provider_bindings_subject"))
                               (js/Error. "binding failed")))
                            (str/starts-with? sql "INSERT INTO")
                            (do (swap! (if @staged staged committed) conj sql) (js/Promise.resolve (result [])))
                            (str/includes? sql "email") (js/Promise.resolve (result []))
                            :else (js/Promise.resolve (result [{:id (aget params 0)}]))))}))
        direct (client)]
    {:pool #js {:query (.-query direct) :connect #(js/Promise.resolve (client))}
     :committed committed :commands commands :released released}))

(defn- ^:async google-callback!
  [fixture & [{:keys [query-state] :or {query-state "fixture-state"}}]]
  (let [responses (atom []) actors (atom []) jar (atom nil)
        callback (get (routes google-routes/register-google-routes!) [:get "/api/auth/google/callback"])
        start (get (routes google-routes/register-google-routes!) [:get "/api/auth/google/start"])]
    (with-redefs [db/pool (delay (:pool fixture))
                  cfg/get-in-config (fn [path] (case path [:jwt/secret] "fixture-secret" [:jwt/issuer] "axxium" "https://fixture.test"))
                  google/configured? (fn [] true)
                  google/random-secret (fn [] "fixture-state")
                  google/authorize-url (fn [& _] "https://google.test")
                  google/verified-profile! (fn [& _] (js/Promise.resolve {:subject "g-1" :email "a@b.test" :display-name "A"}))
                  http/set-state-cookie! (fn ([_ _ value _] (reset! jar value)) ([_ _ value _ _] (reset! jar value)))
                  http/clear-state-cookie! (fn ([_ _] nil) ([_ _ _] nil))
                  http/query-param (fn [_ k] (if (= k "state") query-state "code"))
                  http/cookie (fn [_ _] @jar)
                  session/create-session! (fn [actor] (swap! actors conj (:id actor)) (js/Promise.resolve {:token "t"}))
                  session/set-session-cookie (fn [& _] nil)
                  http/redirect! (fn [_ path] (swap! responses conj {:redirect path}))
                  http/send! (fn [_ status body] (swap! responses conj {:status status :body body}))]
      (await (start {} nil))
      (reset! responses [])
      (await (callback {} nil))
      {:responses @responses :actors @actors})))

(defn- ^:async google-binding-failure-rolls-back-entity-and-actor* []
  (let [fixture (google-db {:fail-binding? true})
        outcome (await (google-callback! fixture))]
    (is (empty? (:actors outcome)))
    (is (= 502 (:status (first (:responses outcome)))))
    (is (empty? @(:committed fixture)))
    (is (some #{"ROLLBACK"} @(:commands fixture)))
    (is (= 1 @(:released fixture)))))

(defn- ^:async google-binding-conflict-recovers-winning-actor* []
  (let [fixture (google-db {:conflict? true})
        outcome (await (google-callback! fixture))]
    (is (= ["winner"] (:actors outcome)))
    (is (= [{:redirect "/portal/index.html"}] (:responses outcome)))
    (is (empty? @(:committed fixture)))))

(defn- ^:async google-state-is-stateless-and-tamper-evident* []
  (let [keys {:secret "fixture-secret" :issuer "axxium"}
        sealed (await (jose/sign-google-state! {:state "s" :nonce "n" :verifier "v"} keys))]
    (is (= {:state "s" :nonce "n" :verifier "v"} (await (jose/verify-google-state! sealed keys))))
    (is (nil? (await (jose/verify-google-state! (str sealed "x") keys))))
    (is (nil? (await (jose/verify-google-state! sealed (assoc keys :secret "other")))))))

(defn- ^:async sign-raw-google-state!
  "Sign an arbitrary payload exactly as the state sealer does, bypassing its input contract."
  [payload secret]
  (-> (new jose/SignJWT (clj->js payload))
      (.setProtectedHeader #js {"alg" "HS256" "typ" "JWT"})
      (.setIssuedAt)
      (.setIssuer "axxium")
      (.setAudience "axxium-google-oauth-state")
      (.setExpirationTime "5m")
      (.sign (.encode (new (.-TextEncoder js/globalThis)) secret))
      (await)))

(defn- ^:async google-state-rejects-signed-payloads-outside-the-contract* []
  (let [keys {:secret "fixture-secret" :issuer "axxium"}]
    (doseq [payload [{:state "s" :nonce "n"}
                     {:state "s" :nonce "n" :verifier 42}
                     {:state "" :nonce "n" :verifier "v"}
                     {:nonce "n" :verifier "v"}
                     {:state "s" :nonce nil :verifier "v"}]]
      (is (nil? (await (jose/verify-google-state! (await (sign-raw-google-state! payload "fixture-secret")) keys)))
          (str "signed payload outside the Google-state contract was admitted: " (pr-str payload))))
    (is (= {:state "s" :nonce "n" :verifier "v"}
           (await (jose/verify-google-state! (await (sign-raw-google-state! {:state "s" :nonce "n" :verifier "v" :extra 1} "fixture-secret")) keys))))
    (is (= :refused
           (try (await (jose/sign-google-state! {:state "s" :nonce "n"} keys))
                :sealed
                (catch :default _ :refused)))
        "sealing claims outside the Google-state contract must throw")))

(defn- ^:async google-start!
  [configured?]
  (let [start (get (routes google-routes/register-google-routes!) [:get "/api/auth/google/start"])
        responses (atom []) cookies (atom []) redirects (atom [])]
    (with-redefs [cfg/get-in-config (fn [path] (case path [:jwt/secret] "fixture-secret" [:jwt/issuer] "axxium" "https://fixture.test"))
                  google/configured? (fn [] configured?)
                  google/random-secret (fn [] "fixture-state")
                  google/authorize-url (fn [& _] "https://google.test")
                  http/set-state-cookie! (fn ([_ _ value secure?] (swap! cookies conj [value secure?]))
                                            ([_ _ value secure? _] (swap! cookies conj [value secure?])))
                  http/redirect! (fn [_ path] (swap! redirects conj path))
                  http/send! (fn [_ status body] (swap! responses conj {:status status :body body}))]
      (await (start {} nil))
      {:responses @responses :cookies @cookies :redirects @redirects})))

(defn- ^:async check-google-start-route!
  []
  (let [refused (await (google-start! false))
        started (await (google-start! true))
        [sealed secure?] (first (:cookies started))]
    (is (= [{:status 503 :body {:error "Google redirect URI is not registered for this Axxium origin"}}]
           (:responses refused)))
    (is (empty? (:cookies refused)))
    (is (empty? (:redirects refused)))
    (is (empty? (:responses started)))
    (is (= ["https://google.test"] (:redirects started)))
    (is (= 1 (count (:cookies started))))
    (is (true? secure?))
    (is (= {:state "fixture-state" :nonce "fixture-state" :verifier "fixture-state"}
           (await (jose/verify-google-state! sealed {:secret "fixture-secret" :issuer "axxium"}))))))

;; cljs.test does not await a Promise returned by a deftest, so this one uses
;; `async`/`done` to keep its assertions inside the reported run.
(deftest google-start-route-seals-state-or-refuses-without-side-effects
  (async done
    (-> (check-google-start-route!)
        (.catch (fn [e] (is false (str "start route check threw: " (pr-str e)))))
        (.finally done))))

(defn- ^:async google-callback-rejects-mismatched-state* []
  (let [fixture (google-db {})
        outcome (await (google-callback! fixture {:query-state "forged"}))]
    (is (= [{:status 400 :body {:error "Invalid or expired Google sign-in state"}}] (:responses outcome)))))

(defn- ^:async sign-raw-google-state-without-exp!
  "Sign state claims with HS256 but omit exp, for verifying requiredClaims."
  [payload secret]
  (-> (new jose/SignJWT (clj->js payload))
      (.setProtectedHeader #js {"alg" "HS256" "typ" "JWT"})
      (.setIssuedAt)
      (.setIssuer "axxium")
      (.setAudience "axxium-google-oauth-state")
      (.sign (.encode (new (.-TextEncoder js/globalThis)) secret))
      (await)))

(defn- ^:async google-state-rejects-empty-secret-and-missing-exp* []
  (let [keys {:secret "fixture-secret" :issuer "axxium"}
        sealed (await (jose/sign-google-state! {:state "s" :nonce "n" :verifier "v"} keys))]
    (is (= :refused
           (try (await (jose/sign-google-state! {:state "s" :nonce "n" :verifier "v"} {:secret "" :issuer "axxium"}))
                :sealed
                (catch :default _ :refused)))
        "empty secret must not seal Google OAuth state")
    (is (= :refused
           (try (await (jose/sign-google-state! {:state "s" :nonce "n" :verifier "v"} {:secret nil :issuer "axxium"}))
                :sealed
                (catch :default _ :refused)))
        "nil secret must not seal Google OAuth state")
    (is (nil? (await (jose/verify-google-state! sealed {:secret "" :issuer "axxium"})))
        "empty secret must not verify")
    (is (nil? (await (jose/verify-google-state! sealed {:secret nil :issuer "axxium"})))
        "nil secret must not verify")
    (is (nil? (await (jose/verify-google-state!
                      (await (sign-raw-google-state-without-exp! {:state "s" :nonce "n" :verifier "v"} "fixture-secret"))
                      keys)))
        "state tokens without exp must not verify")))

(deftest google-state-rejects-empty-secret-and-missing-exp
  (async done (-> (google-state-rejects-empty-secret-and-missing-exp*) (.catch (fn [e] (is false (str "google-state-rejects-empty-secret-and-missing-exp threw: " (pr-str e))))) (.finally done))))

(deftest agent-creation-contract
  (let [admit (fn [body] (let [r (agent-shape/creation-request body)]
                           (when (actor-law/valid-agent-creation-request? r) r)))]
    (is (= {:display-name "Bot"} (agent-shape/creation-request {:display-name "  Bot "})))
    (is (= {:display-name "Bot"} (admit {:display-name "  Bot "})))
    (is (nil? (admit {:display-name "   "})))
    (is (nil? (admit {:display-name 42})))
    (is (nil? (admit {})))
    (is (nil? (admit {:display-name (apply str (repeat 201 "x"))})))))

(defn- ^:async logout-clears-cookie-when-session-deletion-fails* []
  (let [cleared (atom 0) response (atom nil)]
    (with-redefs [session/extract-auth-token (fn [_] "token")
                  session/delete-session! (fn [_] (js/Promise.reject (js/Error. "db down")))
                  session/clear-session-cookie (fn [_] (swap! cleared inc))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (await ((get (routes auth/register-auth-routes!) [:post "/api/auth/logout"]) {} nil))
      (is (= 1 @cleared))
      (is (= 500 (:status @response)))
      (is (= {:error "Logout failed"} (:body @response))))))

(defn- ^:async anonymous-at-identity-lookup-returns-401* []
  (let [response (atom nil)
        handler (get (routes atproto/register-atproto-routes!) [:get "/api/atproto/resolve"])]
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve nil))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (await (handler {} nil))
      (is (= 401 (:status @response))))
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id "h" :auth/roles []}))
                  db/query-one-sql (fn [_] (js/Promise.resolve {:kind "human"}))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (await (handler {} nil))
      (is (= 403 (:status @response))))))

(defn- ^:async invalid-agent-creation-is-rejected-by-the-route-before-db-effects* []
  (let [response (atom nil) writes (atom 0)
        handler (get (routes agents/register-agent-routes!) [:post "/api/actors/agents"])]
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id "admin" :auth/roles [:axxium/system-admin]}))
                  db/query-one-sql (fn [_] (js/Promise.resolve {:kind "human"}))
                  db/with-transaction! (fn [_] (swap! writes inc) (js/Promise.resolve nil))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))]
      (doseq [body [{} {:display-name "   "} {:display-name 42} {:display-name (apply str (repeat 201 "x"))}]]
        (reset! response nil)
        (with-redefs [http/body (fn [_] body)]
          (await (handler {} nil)))
        (is (= 400 (:status @response)))))
    (is (zero? @writes))))

;; cljs.test does not await a Promise returned by a deftest; without `async`/`done`
;; these assertions ran after the summary and their failures were never reported.
(deftest google-binding-failure-rolls-back-entity-and-actor
  (async done (-> (google-binding-failure-rolls-back-entity-and-actor*) (.catch (fn [e] (is false (str "google-binding-failure-rolls-back-entity-and-actor threw: " (pr-str e))))) (.finally done))))

(deftest google-binding-conflict-recovers-winning-actor
  (async done (-> (google-binding-conflict-recovers-winning-actor*) (.catch (fn [e] (is false (str "google-binding-conflict-recovers-winning-actor threw: " (pr-str e))))) (.finally done))))

(deftest google-state-is-stateless-and-tamper-evident
  (async done (-> (google-state-is-stateless-and-tamper-evident*) (.catch (fn [e] (is false (str "google-state-is-stateless-and-tamper-evident threw: " (pr-str e))))) (.finally done))))

(deftest google-callback-rejects-mismatched-state
  (async done (-> (google-callback-rejects-mismatched-state*) (.catch (fn [e] (is false (str "google-callback-rejects-mismatched-state threw: " (pr-str e))))) (.finally done))))

(deftest logout-clears-cookie-when-session-deletion-fails
  (async done (-> (logout-clears-cookie-when-session-deletion-fails*) (.catch (fn [e] (is false (str "logout-clears-cookie-when-session-deletion-fails threw: " (pr-str e))))) (.finally done))))

(deftest anonymous-at-identity-lookup-returns-401
  (async done (-> (anonymous-at-identity-lookup-returns-401*) (.catch (fn [e] (is false (str "anonymous-at-identity-lookup-returns-401 threw: " (pr-str e))))) (.finally done))))

(deftest invalid-agent-creation-is-rejected-by-the-route-before-db-effects
  (async done (-> (invalid-agent-creation-is-rejected-by-the-route-before-db-effects*) (.catch (fn [e] (is false (str "invalid-agent-creation-is-rejected-by-the-route-before-db-effects threw: " (pr-str e))))) (.finally done))))

(deftest google-state-rejects-signed-payloads-outside-the-contract
  (async done (-> (google-state-rejects-signed-payloads-outside-the-contract*) (.catch (fn [e] (is false (str "google-state-rejects-signed-payloads-outside-the-contract threw: " (pr-str e))))) (.finally done))))
