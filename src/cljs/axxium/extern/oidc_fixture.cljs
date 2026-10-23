(ns axxium.extern.oidc-fixture
  "Native integration-test entry point; never mounted as an application route."
  (:require [axxium.extern.oidc-provider :as provider]
            [axxium.extern.oidc-http :as transport]
            [axxium.extern.oidc-store :as store]))

(defn create-provider
  "Decode synthetic native fixture data around the ordinary provider boundary."
  [pool native-config native-lookup]
  (provider/create-provider!
    pool (js->clj native-config :keywordize-keys true)
    (fn ^:async lookup-fixture [subject]
      (some-> (await (native-lookup subject)) (js->clj :keywordize-keys true)))
    (fn [grant-id subject] (store/session-grant-active? pool grant-id subject))))

(defn install-http
  "Decode native fixture options around the ordinary optional HTTP transport."
  [app pool options native-session native-lookup]
  (transport/install! app pool (js->clj options :keywordize-keys true)
    (fn ^:async session-fixture [token]
      (some-> (await (native-session token)) (js->clj :keywordize-keys true)))
    (fn ^:async actor-fixture [subject]
      (some-> (await (native-lookup subject)) (js->clj :keywordize-keys true)))))
