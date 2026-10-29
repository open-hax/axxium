(ns axxium.extern.authorization-boundaries-test
  "Synthetic protocol transport fixtures; no live identities or network listeners."
  (:require ["jose" :as jose]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [axxium.extern.oidc-http :as oidc-http]
            [axxium.extern.oidc-provider :as engine]
            [axxium.extern.oidc-store :as store]
            [axxium.extern.relay :as relay]
            [cljs.test :refer [async deftest is]]))

(defn- ^:async interaction-form-cardinality* []
  (let [directory (.mkdtempSync fs (.join path (.tmpdir os) "axxium-form-contract-"))
        file (.join path directory "config.json")
        parser (atom nil)
        close-hook (atom nil)
        routes #js {:addContentTypeParser (fn [_type _options handler] (reset! parser handler))
                    :get (fn [& _] nil) :post (fn [& _] nil)}
        app #js {:addHook (fn [event handler] (when (= event "onClose") (reset! close-hook handler)))
                 :register (fn [register!] (register! routes))}]
    (try
      (.writeFileSync fs file "{\"issuer\":\"https://authority.example/oidc\",\"cookie-keys\":[\"synthetic-cookie-key-at-least-32-characters\"]}" #js {:mode 384})
      (with-redefs [store/initialize! (fn [_] (js/Promise.resolve nil))
                    relay/install! (fn [& _] (js/Promise.resolve nil))
                    engine/create-provider! (fn [& _] #js {:callback (fn [] (fn [& _] nil))})]
        (await (oidc-http/install! app nil
                 {:config-file file :public-base-url "https://authority.example" :session-cookie-name "session"}
                 (fn [_] (js/Promise.resolve nil)) (fn [_] (js/Promise.resolve nil)))))
      (is (fn? @parser))
      (doseq [body ["csrf=a&csrf=b&decision=approve" "csrf=a&decision=approve&decision=deny"
                    "csrf=a" "decision=approve"]]
        (let [observed (atom nil)]
          (@parser nil body (fn [error value] (reset! observed {:status (some-> error .-statusCode) :value value})))
          (is (= 400 (:status @observed)))
          (is (nil? (:value @observed)))))
      (let [observed (atom nil)]
        (@parser nil "csrf=a&decision=approve"
                 (fn [error value] (reset! observed {:error error :value (js->clj value :keywordize-keys true)})))
        (is (= {:error nil :value {:csrf "a" :decision "approve"}} @observed)))
      (finally
        (when-let [close! @close-hook] (await (close! nil)))
        (.rmSync fs directory #js {:recursive true :force true})))))

(deftest interaction-form-requires-one-csrf-and-one-decision
  (async done (-> (interaction-form-cardinality*)
                  (.catch (fn [e] (is false (str "Interaction form fixture rejected: " (pr-str e)))))
                  (.finally done))))

(defn- reply-fixture []
  (let [response (atom {:status 200})
        holder (atom nil)
        reply #js {:header (fn [& _] @holder)
                   :code (fn [status] (swap! response assoc :status status) @holder)
                   :send (fn [body] (swap! response assoc :body (js->clj body :keywordize-keys true)))}]
    (reset! holder reply)
    {:reply reply :response response}))

(defn- ^:async relay-transport-admission* []
  (let [pair (await (.generateKeyPair jose "RS256" #js {:extractable true}))
        jwk (assoc (js->clj (await (.exportJWK jose (.-privateKey pair))) :keywordize-keys true) :kid "fixture")
        secret (apply str (repeat 32 "n"))
        grant {:node-id "fixture-node" :secret secret :owner-subject "fixture-owner"
               :broker-audience "https://broker.example" :enabled true
               :expires-at (+ (js/Math.floor (/ (.now js/Date) 1000)) 60)
               :allowed-models ["test-model"] :allowed-tenants ["default"]}
        startup {:issuer "https://authority.example/oidc"
                 :cookie-keys [(apply str (repeat 32 "k"))]
                 :clients [{:id "browser" :secret (apply str (repeat 32 "b"))
                            :redirect-uris ["https://client.example/callback"]}]
                 :jwks {:keys [jwk]} :node-grants [grant]}
        current (atom startup)
        handler (atom nil)
        app #js {:post (fn [_endpoint _options issue!] (reset! handler issue!))}
        request #js {:protocol "https" :body #js {:node-id "fixture-node" :secret secret}}]
    (await (relay/install! app startup #(deref current)
             (fn [id] (js/Promise.resolve {:id id :status "active"}))))
    (let [{:keys [reply response]} (reply-fixture)]
      (await (@handler request reply))
      (is (= 200 (:status @response)))
      (is (string? (get-in @response [:body :lease]))))
    (swap! current assoc :issuer "https://changed.example/oidc")
    (let [{:keys [reply response]} (reply-fixture)]
      (await (@handler request reply))
      (is (= {:status 403 :body {:error "Relay authorization refused"}} @response)))))

(deftest relay-accepts-minimum-secret-and-refuses-authority-drift
  (async done (-> (relay-transport-admission*)
                  (.catch (fn [e] (is false (str "Relay transport fixture rejected: " (pr-str e)))))
                  (.finally done))))
