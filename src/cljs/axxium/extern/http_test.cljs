(ns axxium.extern.http-test
  (:require [axxium.extern.fastify :as fastify]
            [axxium.extern.http :as http]
            [cljs.test :refer [async deftest is]]))

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

(defn- fastify-like-request
  "Request whose constructor is not js/Object, like Fastify's Request."
  [ip]
  (let [Request (fn [])]
    (set! (.. Request -prototype -ip) ip)
    (new Request)))

(deftest client-ip-reads-non-plain-request-objects
  ;; CodeRabbit PRRT_kwDOU4Vg086u6dXz: object? is false for Fastify's Request.
  (is (= "203.0.113.7" (http/client-ip (fastify-like-request "203.0.113.7"))))
  (is (= "198.51.100.9" (http/client-ip #js {:ip "198.51.100.9"})))
  (is (nil? (http/client-ip nil)))
  (is (nil? (http/client-ip #js {}))))

(deftest client-ip-reads-real-fastify-request
  (async done
    (let [app (fastify/create-app)]
      (.get app "/ip" (fn [req reply] (.send reply #js {:ip (http/client-ip req)})))
      (-> (.inject app #js {:method "GET" :url "/ip" :remoteAddress "203.0.113.7"})
          (.then (fn [res]
                   (is (= "203.0.113.7" (.-ip (js/JSON.parse (.-body res)))))))
          (.catch (fn [e] (is false (str "fastify inject rejected: " e))))
          (.finally (fn [] (.close app) (done)))))))
