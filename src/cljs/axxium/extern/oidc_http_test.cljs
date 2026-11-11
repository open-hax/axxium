(ns axxium.extern.oidc-http-test
  "Synthetic HTTP response checks for the OIDC consent policy."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [axxium.auth.session :as session]
            [axxium.extern.oidc-http :as oidc-http]
            [axxium.extern.oidc-provider :as engine]
            [axxium.extern.oidc-store :as store]
            [axxium.extern.relay :as relay]
            [clojure.string :as str]
            [cljs.test :refer [async deftest is]]))

(defn- ^:async consent-csp* [registered actor-messaging? cases]
  (let [directory (.mkdtempSync fs (.join path (.tmpdir os) "axxium-csp-contract-"))
        file (.join path directory "config.json")
        origin "https://authority.example"
        uid "fixture_interaction"
        owner "did:web:recipient.example"
        bound-actor-did "did:web:authority.example:actors:human:work"
        proof {:principal {:auth/did owner} :actor-id "recipient"}
        params #js {}
        details #js {:uid uid :params params}
        provider #js {:callback (fn [] (fn [& _] nil))
                      :interactionDetails (fn [& _] (js/Promise.resolve details))}
        config {:issuer (str origin "/oidc")
                :cookie-keys ["synthetic-cookie-key-at-least-32-characters"]
                :mailbox-client-id "mailbox" :mailbox-resource (str origin "/api/mailbox/mcp")
                :mailbox-actor-messaging? actor-messaging?
                :clients [{:id "mailbox" :redirect-uris [registered]}
                          {:id "ordinary" :redirect-uris ["https://other.example/callback"]}]}
        handler (atom nil)
        submit-handler (atom nil)
        displayed-form (atom nil)
        close-hook (atom nil)
        routes #js {:addContentTypeParser (fn [& _] nil)
                    :get (fn [_ f] (reset! handler f))
                    :post (fn [_ f] (reset! submit-handler f))}
        app #js {:addHook (fn [event f] (when (= event "onClose") (reset! close-hook f)))
                 :register (fn [register!] (register! routes))}
        request #js {:raw #js {} :params #js {:uid uid}
                     :headers #js {:cookie "session=synthetic"} :protocol "https"}
        install! (fn [actor-did]
                   (oidc-http/install! app nil
                     {:config-file file :public-base-url origin :session-cookie-name "session"
                      :mailbox-actor-did owner :mailbox-bound-actor-did actor-did
                      :mailbox-available? true}
                     (fn [_] (js/Promise.resolve {:id "ordinary" :status "active"}))
                     (fn [_] (js/Promise.resolve nil))))]
    (try
      (.writeFileSync fs file (js/JSON.stringify (clj->js config)) #js {:mode 384})
      (with-redefs [store/initialize! (fn [_] (js/Promise.resolve nil))
                    relay/install! (fn [& _] (js/Promise.resolve nil))
                    engine/create-provider! (fn [& _] provider)
                    session/atproto-session-proof (fn [_] (js/Promise.resolve proof))
                    session/active-atproto-session? (fn [_] (js/Promise.resolve true))]
        (let [oauth (await (install! bound-actor-did))]
          (is (= actor-messaging? (:actor-messaging? oauth)))
          (is (= (cond-> ["mailbox:events"] actor-messaging? (conj "mailbox:actors")) (:scopes oauth))))
        (doseq [[client redirect allowed scope actor-disclosure?] cases]
          (aset params "client_id" client)
          (aset params "redirect_uri" redirect)
          (aset params "scope" scope)
          (let [headers (atom {})
                body (atom nil)
                seed (atom nil)
                reply #js {:header (fn [name value] (swap! headers assoc name value))
                           :type (fn [_] nil) :setCookie (fn [name value _] (reset! seed (str name "=" value)))
                           :send (fn [value] (reset! body value))}]
            (await (@handler request reply))
            (is (= (str "default-src 'none'; form-action 'self'" allowed
                        "; base-uri 'none'; frame-ancestors 'none'")
                   (get @headers "Content-Security-Policy")))
            (is (= (if (= client "mailbox") "same-origin" "no-referrer")
                   (get @headers "Referrer-Policy")))
            (is (re-find #"name=csrf" @body))
            (is (= (boolean actor-disclosure?)
                   (boolean (re-find #"discover eligible actors controlled by your human account" @body))))
            (is (= (boolean actor-disclosure?) (.includes @body bound-actor-did)))
            (when actor-disclosure?
              (reset! displayed-form {:cookie @seed :csrf (second (re-find #"name=csrf value='([^']+)'" @body))}))))
        (when-let [{:keys [cookie csrf]} @displayed-form]
          (is (re-matches #"[A-Za-z0-9_-]{43}" csrf))
          (is (str/starts-with? cookie "__Host-axxium_oidc_csrf="))
          (when-let [close! @close-hook] (await (close! nil)))
          (await (install! "did:web:authority.example:actors:another:work"))
          (let [status (atom nil)
                reply #js {:header (fn [& _] nil) :send (fn [_] nil)}
                request #js {:raw #js {} :params #js {:uid uid}
                             :headers #js {:origin origin :cookie (str "session=synthetic; " cookie)}
                             :body #js {:csrf csrf :decision "allow"}}]
            (aset reply "code" (fn [value] (reset! status value) reply))
            (await (@submit-handler request reply))
            (is (= 403 @status) "An actor remap invalidates a previously rendered consent form."))))
      (finally
        (when-let [close! @close-hook] (await (close! nil)))
        (.rmSync fs directory #js {:recursive true :force true})))))

(defn- ^:async mailbox-consent-csp* []
  (let [registered "https://connector.example/registered"
        malformed "https://unsafe;host.example/callback"]
    (await (consent-csp* registered false
             [["mailbox" registered " https://connector.example"]
              ["mailbox" (str registered "/unregistered") ""]
              ["ordinary" "https://other.example/callback" ""]
              ["mailbox" registered " https://connector.example" "openid mailbox:events mailbox:actors" false]]))
    (await (consent-csp* registered true
             [["mailbox" registered " https://connector.example" "openid mailbox:events" false]
              ["mailbox" registered " https://connector.example" "openid mailbox:events mailbox:actors" true]]))
    (await (consent-csp* malformed false [["mailbox" malformed ""]]))))

(deftest only-mailbox-consent-allows-the-registered-redirect-origin
  (async done (-> (mailbox-consent-csp*)
                  (.catch (fn [e] (is false (str "Mailbox consent CSP fixture rejected: " (pr-str e)))))
                  (.finally done))))

(defn- ^:async actor-scope-proof* []
  (let [issuer "https://authority.example/oidc"
        resource "https://authority.example/api/mailbox/mcp"
        owner "did:web:recipient.example"
        bound-actor-did "did:web:authority.example:actors:human:work"
        remapped-did "did:web:authority.example:actors:unrelated:work"
        config {:issuer issuer :mailbox-client-id "mailbox" :mailbox-resource resource
                :mailbox-actor-messaging? true}
        proof {:principal {:auth/did owner :auth/method :atproto} :actor-id "recipient"
               :cred-id "credential" :token-hash "digest" :oauth/grant-id "grant"
               :oauth/client-id "mailbox" :oauth/resource resource}
        token #js {:clientId "mailbox" :aud resource :accountId "recipient" :grantId "grant"
                   :extra #js {:iss issuer} :isValid true :isSenderConstrained (fn [] false)
                   :scope "mailbox:events"}
        provider #js {:issuer issuer :AccessToken #js {:find (fn [_] (js/Promise.resolve token))}}
        scope-present? (atom true)
        require-scopes (atom [])
        stored-proof (fn [required-actor-did]
                       (swap! require-scopes conj required-actor-did)
                       (js/Promise.resolve
                         (when (or (nil? required-actor-did)
                                   (and @scope-present? (= bound-actor-did required-actor-did)))
                           proof)))]
    (with-redefs [store/mailbox-grant-proof!
                  (fn ([_ _ _ _ _] (stored-proof nil))
                      ([_ _ _ _ _ required-actor-did] (stored-proof required-actor-did)))]
      (is (= proof (await (engine/mailbox-access-proof! provider nil config owner bound-actor-did "token"))))
      (is (= [nil] @require-scopes))
      (set! (.-scope token) "mailbox:events mailbox:actors")
      (is (= (assoc proof :mailbox/actors? true)
             (await (engine/mailbox-access-proof! provider nil config owner bound-actor-did "token"))))
      (is (= [nil bound-actor-did] @require-scopes))
      (is (nil? (await (engine/mailbox-access-proof! provider nil (dissoc config :mailbox-actor-messaging?)
                                                     owner bound-actor-did "token"))))
      (is (nil? (await (engine/mailbox-access-proof! provider nil config owner nil "token"))))
      (is (nil? (await (engine/mailbox-access-proof! provider nil config owner remapped-did "token"))))
      (is (nil? (await (engine/current-mailbox-grant! nil config owner "grant" "recipient" remapped-did))))
      (reset! scope-present? false)
      (is (nil? (await (engine/mailbox-access-proof! provider nil config owner bound-actor-did "token"))))
      (is (nil? (await (engine/current-mailbox-grant! nil config owner "grant" "recipient" bound-actor-did))))
      (is (= proof (await (engine/current-mailbox-grant! nil config owner "grant" "recipient")))))))

(deftest actor-capability-requires-both-opaque-token-and-current-grant-scope
  (async done (-> (actor-scope-proof*)
                  (.catch (fn [e] (is false (str "Mailbox actor-scope fixture rejected: " (pr-str e)))))
                  (.finally done))))
