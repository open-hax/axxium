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
