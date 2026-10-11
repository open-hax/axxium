(ns axxium.extern.http-test
  (:require [axxium.extern.http :as http]
            [cljs.test :refer [deftest is]]))

(deftest oauth-start-canonicalizes-cookie-host-and-preserves-query
  (let [redirected (atom nil)
        handled (atom false)
        start (http/with-canonical-origin
               "http://127.0.0.1:8787" "/api/auth/atproto/start"
               (fn [_ _] (reset! handled true)))
        reply #js {:redirect (fn [url] (reset! redirected url))}]
    (start #js {:headers #js {:host "localhost:8787"}
                :raw #js {:url "/api/auth/atproto/start?identity=calliope.test&link=1"}}
           reply)
    (is (= "http://127.0.0.1:8787/api/auth/atproto/start?identity=calliope.test&link=1"
           @redirected))
    (is (false? @handled))
    (start #js {:headers #js {:host "127.0.0.1:8787"}
                :raw #js {:url "/api/auth/atproto/start?identity=calliope.test"}}
           reply)
    (is @handled)))

(deftest portal-canonicalizes-before-session-linking
  (let [request #js {:headers #js {:host "localhost:8787"}
                     :raw #js {:url "/portal/index.html?tab=accounts"}}]
    (is (= "http://127.0.0.1:8787/portal/index.html?tab=accounts"
           (http/canonical-url "http://127.0.0.1:8787"
                               "/portal/index.html" request)))))

(deftest portal-hook-ignores-malformed-nonportal-requests
  (let [hook (atom nil)
        completed (atom 0)
        redirected (atom nil)
        app #js {:addHook (fn [_ handler] (reset! hook handler))}
        reply #js {:redirect (fn [target] (reset! redirected target))}
        done #(swap! completed inc)]
    (http/register-portal-origin! app "http://127.0.0.1:8787")
    (@hook #js {:method "GET" :headers #js {:host "localhost:8787"}
                :raw #js {:url "//["}} reply done)
    (is (= 1 @completed))
    (is (nil? @redirected))
    (@hook #js {:method "GET" :headers #js {:host "localhost:8787"}
                :raw #js {:url "/portal/index.html?tab=accounts"}}
           reply done)
    (is (= "http://127.0.0.1:8787/portal/index.html?tab=accounts"
           @redirected))
    (reset! redirected nil)
    (@hook #js {:method "GET" :headers #js {:host "localhost:8787"}
                :raw #js {:url "http://localhost:8787/portal/index.html?tab=agents"}}
           reply done)
    (is (= "http://127.0.0.1:8787/portal/index.html?tab=agents"
           @redirected))))
