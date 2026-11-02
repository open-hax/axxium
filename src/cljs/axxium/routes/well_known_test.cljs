(ns axxium.routes.well-known-test
  "Well-known routes publish the public key and refuse anything else."
  (:require [axxium.config :as cfg]
            [axxium.extern.http :as http]
            [axxium.extern.signing-key :as signing-key]
            [axxium.law.signing :as law]
            [axxium.routes.well-known :as well-known]
            [cljs.test :refer [async deftest is]]))

(defn- handlers []
  (let [found (atom {})]
    (with-redefs [http/get! (fn [_ path handler] (swap! found assoc path handler))]
      (well-known/register-well-known-routes! nil))
    @found))

(defn- call [handler]
  (let [sent (atom nil)
        headers (atom [])]
    (with-redefs [http/send! (fn [_ status body] (reset! sent {:status status :body body}))
                  http/set-header! (fn [_ name value] (swap! headers conj [name value]))]
      (handler nil nil))
    {:sent @sent :headers @headers}))

(defn- ^:async published-documents-have-no-private-member* []
  (let [material (await (signing-key/generate-ephemeral!))
        routes (handlers)]
    (signing-key/install! material)
    (try
      (with-redefs [cfg/get-in-config (fn [path]
                                        (when (= path [:axxium/public-base-url])
                                          "https://axxium.promethean.rest"))]
        (let [jwks (call (get routes "/.well-known/jwks.json"))
              did (call (get routes "/.well-known/did.json"))]
          (is (= 200 (:status (:sent jwks))))
          (is (= [["Cache-Control" "public, max-age=300"]] (:headers jwks)))
          (is (law/jwks-document? (:body (:sent jwks))))
          (is (not (law/contains-private-member? (:body (:sent jwks)))))
          (is (= 200 (:status (:sent did))))
          (is (= "did:web:axxium.promethean.rest" (get (:body (:sent did)) "id")))
          (is (law/did-document? (:body (:sent did))))
          (is (not (law/contains-private-member? (:body (:sent did)))))))
      (finally
        (signing-key/unload!)))))

(defn- unavailable [outcome]
  (and (= 500 (:status (:sent outcome)))
       (= {:error "Signing key is not available"} (:body (:sent outcome)))
       (empty? (:headers outcome))))

(defn- ^:async a-missing-or-private-key-is-a-fixed-refusal* []
  (let [routes (handlers)
        jwks (get routes "/.well-known/jwks.json")
        did (get routes "/.well-known/did.json")]
    (signing-key/unload!)
    (is (unavailable (call jwks)))
    (is (unavailable (call did)))
    (with-redefs [signing-key/public-jwk (fn [] {:kty "OKP" :crv "Ed25519" :x "hidden" :d "hidden"})]
      (is (unavailable (call jwks)))
      (is (unavailable (call did))))))

(deftest published-documents-have-no-private-member
  (async done
    (-> (published-documents-have-no-private-member*)
        (.catch (fn [error]
                  (is false (str "well-known publication rejected: " (ex-message error)))))
        (.finally (fn []
                    (signing-key/unload!)
                    (done))))))

(deftest a-missing-or-private-key-is-a-fixed-refusal
  (async done
    (-> (a-missing-or-private-key-is-a-fixed-refusal*)
        (.catch (fn [error]
                  (is false (str "well-known refusal rejected: " (ex-message error)))))
        (.finally (fn []
                    (signing-key/unload!)
                    (done))))))
