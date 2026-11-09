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
            [cljs.test :refer [async deftest is]]))

(defn- ^:async consent-csp* [registered cases]
  (let [directory (.mkdtempSync fs (.join path (.tmpdir os) "axxium-csp-contract-"))
        file (.join path directory "config.json")
        origin "https://authority.example"
        uid "fixture_interaction"
        owner "did:web:recipient.example"
        proof {:principal {:auth/did owner} :actor-id "recipient"}
        params #js {}
        details #js {:uid uid :params params}
        provider #js {:callback (fn [] (fn [& _] nil))
                      :interactionDetails (fn [& _] (js/Promise.resolve details))}
        config {:issuer (str origin "/oidc")
                :cookie-keys ["synthetic-cookie-key-at-least-32-characters"]
                :mailbox-client-id "mailbox" :mailbox-resource (str origin "/api/mailbox/mcp")
                :clients [{:id "mailbox" :redirect-uris [registered]}
                          {:id "ordinary" :redirect-uris ["https://other.example/callback"]}]}
        handler (atom nil)
        close-hook (atom nil)
        routes #js {:addContentTypeParser (fn [& _] nil)
                    :get (fn [_ f] (reset! handler f)) :post (fn [& _] nil)}
        app #js {:addHook (fn [event f] (when (= event "onClose") (reset! close-hook f)))
                 :register (fn [register!] (register! routes))}
        request #js {:raw #js {} :params #js {:uid uid}
                     :headers #js {:cookie "session=synthetic"} :protocol "https"}]
    (try
      (.writeFileSync fs file (js/JSON.stringify (clj->js config)) #js {:mode 384})
      (with-redefs [store/initialize! (fn [_] (js/Promise.resolve nil))
                    relay/install! (fn [& _] (js/Promise.resolve nil))
                    engine/create-provider! (fn [& _] provider)
                    session/atproto-session-proof (fn [_] (js/Promise.resolve proof))
                    session/active-atproto-session? (fn [_] (js/Promise.resolve true))]
        (await (oidc-http/install! app nil
                 {:config-file file :public-base-url origin :session-cookie-name "session"
                  :mailbox-actor-did owner :mailbox-available? true}
                 (fn [_] (js/Promise.resolve {:id "ordinary" :status "active"}))
                 (fn [_] (js/Promise.resolve nil))))
        (doseq [[client redirect allowed] cases]
          (aset params "client_id" client)
          (aset params "redirect_uri" redirect)
          (let [headers (atom {})
                body (atom nil)
                reply #js {:header (fn [name value] (swap! headers assoc name value))
                           :type (fn [_] nil) :setCookie (fn [& _] nil)
                           :send (fn [value] (reset! body value))}]
            (await (@handler request reply))
            (is (= (str "default-src 'none'; form-action 'self'" allowed
                        "; base-uri 'none'; frame-ancestors 'none'")
                   (get @headers "Content-Security-Policy")))
            (is (re-find #"name=csrf" @body)))))
      (finally
        (when-let [close! @close-hook] (await (close! nil)))
        (.rmSync fs directory #js {:recursive true :force true})))))

(defn- ^:async mailbox-consent-csp* []
  (let [registered "https://connector.example/registered"
        malformed "https://unsafe;host.example/callback"]
    (await (consent-csp* registered
             [["mailbox" registered " https://connector.example"]
              ["mailbox" (str registered "/unregistered") ""]
              ["ordinary" "https://other.example/callback" ""]]))
    (await (consent-csp* malformed [["mailbox" malformed ""]]))))

(deftest only-mailbox-consent-allows-the-registered-redirect-origin
  (async done (-> (mailbox-consent-csp*)
                  (.catch (fn [e] (is false (str "Mailbox consent CSP fixture rejected: " (pr-str e)))))
                  (.finally done))))
