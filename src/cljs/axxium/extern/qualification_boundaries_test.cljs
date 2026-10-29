(ns axxium.extern.qualification-boundaries-test
  "Observed public admission and response gaps; synthetic adapters only."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [axxium.auth.session :as session]
            [axxium.db :as db]
            [axxium.extern.http :as http]
            [axxium.extern.oidc-http :as oidc-http]
            [axxium.extern.oidc-provider :as engine]
            [axxium.extern.oidc-store :as store]
            [axxium.extern.relay :as relay]
            [axxium.law.atproto :as atproto]
            [axxium.law.google-state :as google-state]
            [axxium.routes.actor :as actor]
            [axxium.routes.auth-context :as auth]
            [cljs.test :refer [async deftest is]]))

(defn- text [n] (apply str (repeat n "a")))

(deftest sealed-google-state-keeps-every-required-claim-bounded
  (let [valid {:state "state" :nonce "nonce" :verifier "verifier"}]
    (doseq [field [:state :nonce :verifier]]
      (doseq [size [1 512]]
        (is (true? (google-state/valid-google-state? (assoc valid field (text size))))
            (str field " admits the declared inclusive claim boundary " size)))
      (doseq [value [nil false 42 [] {} "" (text 513)]]
        (is (false? (google-state/valid-google-state? (assoc valid field value)))
            (str field " refuses a malformed or oversized claim"))))))

(deftest sdk-state-store-key-remains-nonempty-and-bounded
  (doseq [size [1 2048]]
    (is (true? (atproto/valid-oauth-state-store-key? (text size)))))
  (doseq [value [nil false 42 [] {} "" (text 2049)]]
    (is (false? (atproto/valid-oauth-state-store-key? value)))))

(deftest sdk-saved-state-admission-checks-private-members-before-import
  ;; This is the public shape contract, not a claim that placeholder key
  ;; material is cryptographically valid or that an SDK/network call occurred.
  (let [saved {:iss "https://bsky.social" :verifier "v" :auth-method {:method "none"}}
        schemes [[{:kty "EC" :crv "P-256" :x "x" :y "y" :d "d"}
                  [[:x 512] [:y 512] [:d 512]]]
                 [{:kty "OKP" :crv "Ed25519" :x "x" :d "d"}
                  [[:x 512] [:d 512]]]
                 [{:kty "RSA" :n "n" :e "e" :d "d" :p "p" :q "q" :dp "dp" :dq "dq" :qi "qi"}
                  [[:n 8192] [:e 32] [:d 8192] [:p 4096] [:q 4096] [:dp 4096] [:dq 4096] [:qi 4096]]]]]
    (doseq [[key fields] schemes
            [field maximum] fields]
      (doseq [size [1 maximum]]
        (is (true? (atproto/valid-oauth-saved-state?
                     (assoc saved :dpop-jwk (assoc key field (text size)))))
            (str (:kty key) " " field " accepts its inclusive bound")))
      (doseq [value [nil false 42 "" (text (inc maximum))]]
        (is (false? (atproto/valid-oauth-saved-state?
                      (assoc saved :dpop-jwk (assoc key field value))))
            (str (:kty key) " " field " refuses malformed private material"))))
    (let [key {:kty "EC" :crv "P-256" :x "x" :y "y" :d "d"}
          base (assoc saved :dpop-jwk key)]
      (doseq [field [:iss :verifier]]
        (doseq [size [1 2048]]
          (is (true? (atproto/valid-oauth-saved-state? (assoc base field (text size))))))
        (doseq [value ["" (text 2049) nil 42]]
          (is (false? (atproto/valid-oauth-saved-state? (assoc base field value))))))
      (doseq [size [1 256]]
        (is (true? (atproto/valid-oauth-saved-state?
                     (assoc base :auth-method {:method "private_key_jwt" :kid (text size)})))))
      (doseq [value ["" (text 257) nil 42]]
        (is (false? (atproto/valid-oauth-saved-state?
                      (assoc base :auth-method {:method "private_key_jwt" :kid value}))))))))

(deftest sdk-optional-app-state-preserves-its-public-shape-boundaries
  (let [saved {:iss "https://bsky.social" :verifier "v" :auth-method {:method "none"}
               :dpop-jwk {:kty "EC" :crv "P-256" :x "x" :y "y" :d "d"}}]
    (is (true? (atproto/valid-oauth-saved-state? saved)))
    (doseq [value [nil "" (text 8192)]]
      (is (true? (atproto/valid-oauth-saved-state? (assoc saved :app-state value)))))
    (doseq [value [(text 8193) false 42 [] {}]]
      (is (false? (atproto/valid-oauth-saved-state? (assoc saved :app-state value)))))))

(defn- actor-handlers []
  (let [handlers (atom {})
        register (fn [method] (fn [_ url handler] (swap! handlers assoc [method url] handler)))]
    (with-redefs [http/get! (register :get) http/post! (register :post)]
      (actor/register-actor-routes! nil))
    @handlers))

(defn- ^:async invoke-route* [handler {:keys [context rows actors request auth-error?]
                                    :or {actors [] request {}}}]
  (let [responses (atom []) queries (atom []) writes (atom []) pending (atom rows)]
    (with-redefs [session/resolve-auth-context
                  (fn [_] (if auth-error? (js/Promise.reject (js/Error. "synthetic authentication failure"))
                              (js/Promise.resolve context)))
                  db/query-one-sql (fn [query]
                                     (swap! queries conj query)
                                     (let [row (first @pending)]
                                       (swap! pending rest)
                                       (js/Promise.resolve row)))
                  db/query-all-sql (fn [query] (swap! queries conj query) (js/Promise.resolve actors))
                  db/query-sql (fn [query] (swap! writes conj query) (js/Promise.resolve {:row-count 1}))
                  http/param (fn [req name] (get-in req [:params (keyword name)]))
                  http/query-param (fn [req name] (get-in req [:query (keyword name)]))
                  http/body (fn [req] (:body req))
                  http/send! (fn [_ status body] (swap! responses conj {:status status :body body}))]
      (await (handler request nil)))
    {:responses @responses :queries @queries :writes @writes}))

(def administrator {:auth/actor-id "admin" :auth/roles [:axxium/system-admin]})

(defn- ^:async protected-responses* []
  (let [handler (auth/with-auth (fn [& _] (js/Promise.reject (js/Error. "synthetic handler failure"))))]
    (is (= [{:status 500 :body {:error "Could not complete protected request"}}]
           (:responses (await (invoke-route* handler {:context {:auth/actor-id "human"}}))))))
  (doseq [[_ handler] (actor-handlers)]
    (let [missing (await (invoke-route* handler {:context nil}))
          failed (await (invoke-route* handler {:auth-error? true}))]
      (is (= [{:status 401 :body {:error "Unauthorized"}}] (:responses missing)))
      (is (empty? (:queries missing)))
      (is (empty? (:writes missing)))
      (is (= [{:status 500 :body {:error "Could not complete protected request"}}] (:responses failed)))
      (is (empty? (:writes failed)))))
  (let [handler (get (actor-handlers) [:get "/api/actors/:id"])
        selected {:id "actor-a" :display_name "A" :password_hash "synthetic-private-marker"}]
    (doseq [roles [[:axxium/system-admin] ["axxium/system-admin"]]]
      (let [found (await (invoke-route* handler {:context (assoc administrator :auth/roles roles)
                                                :rows [{:kind "human"} selected]
                                                :request {:params {:id "actor-a"}}}))]
        (is (= [{:status 200 :body {:ok true :actor (dissoc selected :password_hash)}}] (:responses found)))))
    (is (= [{:status 404 :body {:error "Actor not found"}}]
           (:responses (await (invoke-route* handler {:context administrator :rows [{:kind "human"} nil]
                                                       :request {:params {:id "absent"}}})))))
    (doseq [[context entity] [[administrator {:kind "agent"}]
                              [(assoc administrator :auth/roles []) {:kind "human"}]]]
      (let [denied (await (invoke-route* handler {:context context :rows [entity]}))]
        (is (= [{:status 403 :body {:error "System administrator required"}}] (:responses denied)))
        (is (= 1 (count (:queries denied)))))))
  (let [handler (get (actor-handlers) [:get "/api/actors/me"])
        selected {:id "human" :password_hash "synthetic-private-marker"}]
    (is (= [{:status 200 :body {:ok true :actor {:id "human"}}}]
           (:responses (await (invoke-route* handler {:context {:auth/actor-id "human"} :rows [selected]})))))
    (is (= [{:status 404 :body {:error "Actor not found"}}]
           (:responses (await (invoke-route* handler {:context {:auth/actor-id "human"} :rows [nil]})))))))

(deftest protected-actor-responses-preserve-status-and-redaction
  (async done (-> (protected-responses*)
                  (.catch (fn [e] (is false (str "Protected response contract rejected: " (pr-str e)))))
                  (.finally done))))

(defn- ^:async capability-cardinality* []
  (let [handler (get (actor-handlers) [:post "/api/actors/:id/capabilities"])]
    (doseq [n [0 1 64]]
      (let [response (await (invoke-route* handler
                             {:context administrator :rows [{:kind "human"}]
                              :request {:params {:id "actor-a"} :body {:capabilities (vec (repeat n "capability"))}}}))]
        (is (= [{:status 200 :body {:ok true}}] (:responses response)))
        (is (= 1 (count (:writes response))))))
    (doseq [capabilities [(vec (repeat 65 "capability")) [nil] [42] [false] nil "capability" '("capability")]]
      (let [response (await (invoke-route* handler
                             {:context administrator :rows [{:kind "human"}]
                              :request {:params {:id "actor-a"} :body {:capabilities capabilities}}}))]
        (is (= [{:status 400 :body {:error "capabilities must be a list of strings"}}] (:responses response)))
        (is (empty? (:writes response)))))))

(deftest administrator-capability-update-enforces-public-cardinality
  (async done (-> (capability-cardinality*)
                  (.catch (fn [e] (is false (str "Capability contract rejected: " (pr-str e)))))
                  (.finally done))))

(defn- ^:async actor-page-cursor-admission* []
  (let [handler (get (actor-handlers) [:get "/api/actors"])
        selected {:id "actor-a" :password_hash "synthetic-private-marker"}
        success [{:status 200 :body {:ok true :actors [{:id "actor-a"}] :count 1}}]]
    (let [response (await (invoke-route* handler
                           {:context administrator :rows [{:kind "human"}] :actors [selected]}))]
      (is (= success (:responses response)))
      (is (= 2 (count (:queries response)))))
    (doseq [after ["a" (text 256)]]
      (let [response (await (invoke-route* handler
                             {:context administrator :rows [{:kind "human"} {:id after}]
                              :actors [selected] :request {:query {:after after}}}))]
        (is (= success (:responses response)))
        (is (= 3 (count (:queries response))))
        (is (empty? (:writes response)))))
    (let [response (await (invoke-route* handler
                           {:context administrator :rows [{:kind "human"} nil]
                            :request {:query {:after "unknown"}}}))]
      (is (= [{:status 400 :body {:error "Unknown actor cursor"}}] (:responses response)))
      (is (= 2 (count (:queries response))))
      (is (empty? (:writes response))))
    (doseq [after ["" (text 257) false 42 [] {}]]
      (let [response (await (invoke-route* handler
                             {:context administrator :rows [{:kind "human"}]
                              :request {:query {:after after}}}))]
        (is (= [{:status 400 :body {:error "Invalid actor cursor"}}] (:responses response)))
        (is (= 1 (count (:queries response))) "Malformed cursors refuse before any cursor/page query")
        (is (empty? (:writes response)))))))

(deftest administrator-actor-pages-refuse-malformed-or-unknown-cursors
  (async done (-> (actor-page-cursor-admission*)
                  (.catch (fn [e] (is false (str "Actor cursor contract rejected: " (pr-str e)))))
                  (.finally done))))

(defn- reply-fixture []
  (let [response (atom {:status 200 :headers {} :cookies {}})
        holder (atom nil)
        reply #js {:header (fn [name value] (swap! response assoc-in [:headers name] value) @holder)
                   :code (fn [status] (swap! response assoc :status status) @holder)
                   :type (fn [_] @holder)
                   :setCookie (fn [name value _] (swap! response assoc-in [:cookies name] value) @holder)
                   :send (fn [body] (swap! response assoc :body (if (string? body) body (js->clj body :keywordize-keys true))))}]
    (reset! holder reply)
    {:reply reply :response response}))

(defn- ^:async interaction-error-redaction* []
  (let [directory (.mkdtempSync fs (.join path (.tmpdir os) "axxium-error-contract-"))
        file (.join path directory "config.json")
        error (atom nil) handlers (atom {}) close-hook (atom nil)
        uid "contract_interaction" actor {:id "human" :status "active" :display_name "Fixture human"}
        details #js {:uid uid :params #js {:client_id "client" :scope "openid"}
                     :prompt #js {:name "consent"} :session #js {:accountId "human"}}
        provider #js {:callback (fn [] (fn [& _] nil))
                      :interactionDetails (fn [& _] (if @error (js/Promise.reject @error) (js/Promise.resolve details)))}
        routes #js {:addContentTypeParser (fn [& _] nil)
                    :get (fn [url handler] (swap! handlers assoc :get handler))
                    :post (fn [url handler] (swap! handlers assoc :post handler))}
        app #js {:addHook (fn [event handler] (when (= event "onClose") (reset! close-hook handler)))
                 :register (fn [register!] (register! routes))}
        request (fn [cookie] #js {:raw #js {} :params #js {:uid uid} :headers #js {:cookie cookie} :protocol "https"})]
    (try
      (.writeFileSync fs file "{\"issuer\":\"https://authority.example/oidc\",\"cookie-keys\":[\"synthetic-cookie-key-at-least-32-characters\"]}" #js {:mode 384})
      (with-redefs [store/initialize! (fn [_] (js/Promise.resolve nil))
                    relay/install! (fn [& _] (js/Promise.resolve nil))
                    engine/create-provider! (fn [& _] provider)]
        (await (oidc-http/install! app nil
                 {:config-file file :public-base-url "https://authority.example" :session-cookie-name "session"}
                 (fn [_] (js/Promise.resolve actor)) (fn [_] (js/Promise.resolve actor))))
        (doseq [[status expected] [[nil 503] [399 503] [400 400] [401 400] [499 400] [500 503] [503 503]]]
          (let [failure (js/Error. "synthetic detail must stay private")]
            (when (some? status) (aset failure "statusCode" status))
            (reset! error failure)
            (let [{:keys [reply response]} (reply-fixture)]
              (await ((get @handlers :get) (request "session=root-token") reply))
              (is (= expected (:status @response)))
              (is (= {:error "Shared login could not be completed"} (:body @response)))
              (is (= "no-store" (get-in @response [:headers "Cache-Control"]))))
            ;; Obtain a valid Origin/CSRF-bound POST from the actual public GET
            ;; page, rather than duplicating the transport's HMAC implementation.
            (reset! error nil)
            (let [{page-reply :reply page :response} (reply-fixture)]
              (await ((get @handlers :get) (request "session=root-token") page-reply))
              (let [csrf (second (re-find #"name=csrf value='([^']+)'" (:body @page)))
                    [cookie-name seed] (first (:cookies @page))
                    req (request (str "session=root-token; " cookie-name "=" seed))
                    {:keys [reply response]} (reply-fixture)]
                (is (string? csrf))
                (aset (.-headers req) "origin" "https://authority.example")
                (aset req "body" #js {:csrf csrf :decision "approve"})
                (reset! error failure)
                (await ((get @handlers :post) req reply))
                (is (= expected (:status @response)))
                (is (= {:error "Shared login could not be completed"} (:body @response)))
                (is (= "no-store" (get-in @response [:headers "Cache-Control"]))))))))
      (finally
        (when-let [close! @close-hook] (await (close! nil)))
        (.rmSync fs directory #js {:recursive true :force true})))))

(deftest interaction-provider-errors-use-generic-public-statuses
  (async done (-> (interaction-error-redaction*)
                  (.catch (fn [e] (is false (str "Interaction error contract rejected: " (pr-str e)))))
                  (.finally done))))
