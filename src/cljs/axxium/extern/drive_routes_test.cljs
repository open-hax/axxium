(ns axxium.extern.drive-routes-test
  "Route-level failure injection; no Google account or network is used."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.extern.google :as google]
            [axxium.extern.google-drive :as gd]
            [axxium.extern.http :as http]
            [axxium.extern.jose :as jose]
            [axxium.extern.ledger-file :as disk]
            [axxium.extern.signing-key :as signing-key]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.google-drive :as law]
            [axxium.routes.drive :as routes]
            [clio.shape.edn :as edn]
            [cljs.test :refer [async deftest is]]
            ["node:crypto" :as crypto]))

(defn- handlers []
  (let [registered (atom {})]
    (with-redefs [http/get! (fn [_ path handler] (swap! registered assoc path handler))
                  http/post! (fn [_ path handler] (swap! registered assoc path handler))]
      (routes/register-drive-routes! nil))
    @registered))

(defn- ^:async failed-persist* []
  (let [registered (handlers) response (atom nil) durable (atom [])
        fail-next (atom false) remote (atom {}) fetches (atom [])
        key (crypto/randomBytes 32)
        request #js {:body #js {:tool "drive_put" :arguments "{:drive/name \"x.edn\" :drive/value 42}"}}]
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id "alice"}))
                  cfg/get-in-config (fn [ks] (case ks
                                              [:axxium/public-base-url] "https://drive.test"
                                              [:jwt/issuer] "test"
                                              [:drive/ledger-file] "fixture-ledger"
                                              [:drive/token-key-file] "fixture-key"
                                              nil))
                  google/drive-client (fn [] {:client-id "c" :client-secret "s"})
                  tc/read-key-file (fn [_] key)
                  signing-key/current! (fn [] {})
                  jose/verify-google-state! (fn [& _] (js/Promise.resolve {:state "alice.state" :verifier "v"}))
                  http/query-param (fn [_ k] (if (= k "state") "alice.state" "code"))
                  http/cookie (fn [& _] "signed-state")
                  http/clear-state-cookie! (fn [& _] nil)
                  http/redirect! (fn [_ target] (reset! response {:redirect target}))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))
                  disk/load-ledger (fn [_] @durable)
                  disk/append-batch! (fn [_ before after]
                                       (is (= before @durable))
                                       (if (compare-and-set! fail-next true false)
                                         (throw (js/Error. "injected persistence failure"))
                                         (reset! durable after)))
                  gd/exchange-code (fn [fetch _]
                                     (swap! fetches conj fetch)
                                     (js/Promise.resolve {:scope law/drive-file-scope :refresh_token "fixture-refresh"}))
                  gd/refresh-access-token (fn [fetch _]
                                            (swap! fetches conj fetch)
                                            (js/Promise.resolve "at"))
                  gd/list-files (fn [& _] (js/Promise.resolve {:drive/files (vec (vals @remote))}))
                  gd/create-file (fn [_ _ args]
                                   (let [file {:drive/file-id "remote1" :drive/name (:drive/name args)}]
                                     (swap! remote assoc "remote1" file)
                                     (js/Promise.resolve file)))]
      (await ((get registered law/callback-path) nil nil))
      (is (= "/portal/index.html?drive=connected" (:redirect @response)))
      (reset! fail-next true)
      (await ((get registered "/api/drive/mcp") request nil))
      (is (= 500 (:status @response)) "remote success cannot become a successful response when persistence fails")
      (is (= 1 (count @remote)) "Google already created exactly one file")
      (await ((get registered "/api/drive/mcp") request nil))
      (is (= 400 (:status @response)))
      (let [out (edn/read-one (get-in @response [:body :edn]))]
        (is (= :exists-untracked (:error out)))
        (is (= ["remote1"] (:drive/existing out))))
      (is (= 1 (count @remote)) "retry does not duplicate or silently adopt the external file")
      (await ((get registered "/api/drive/mcp")
              #js {:body #js {:tool "drive_list" :arguments "{}"}} nil))
      (is (= 200 (:status @response)) "the rejected queued operation releases the next caller")
      (is (= [] (get-in (edn/read-one (get-in @response [:body :edn])) [:result :resources])))
      (is (seq @fetches))
      (is (every? #(not (identical? js/fetch %)) @fetches)
          "both OAuth exchange and Drive tools receive a timeout-capable fetch"))))

(deftest remote-create-then-persist-failure-can-be-retried-honestly
  (async done
    (-> (failed-persist*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))
