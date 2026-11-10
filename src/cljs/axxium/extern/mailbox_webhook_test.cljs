(ns axxium.extern.mailbox-webhook-test
  (:require [axxium.extern.mailbox-webhook :as webhook]
            [cljs.test :refer [async deftest is]]
            [clojure.string :as str]
            ["node:crypto" :as crypto]))

(defn- encode-key [size]
  (str "whsec_" (.toString (js/Buffer.alloc size 255) "base64")))

(def ^:private callback
  {:id "sub_fixture"
   :url "https://hooks.chatgpt.com/receiver?opaque=1"
   :secret (encode-key 32)})

(def ^:private event
  {"eventId" "evt_fixture"
   "name" "message.available"
   "timestamp" "2026-10-01T12:05:00Z"
   "data" {"test" "字\n🔒"}
   "cursor" nil})

(deftest callbacks-have-canonical-https-dns-destinations
  (is (= "https://hooks.chatgpt.com/cb?q=1"
         (webhook/canonical-url "https://HOOKS.ChatGPT.com:443/a/../cb?q=1")))
  (is (= "https://hooks.chatgpt.com/" (webhook/canonical-url "https://hooks.chatgpt.com")))
  (doseq [value [nil "http://hooks.chatgpt.com/cb" "https:///hooks.chatgpt.com/cb"
                 "https://name@hooks.chatgpt.com/cb" "https://@hooks.chatgpt.com/cb"
                 "https://hooks.chatgpt.com/cb#" "https://hooks.chatgpt.com:8443/cb"
                 "https://hooks.chatgpt.com\\other" "https://hooks.chatgpt.com/cb\n"
                 "https://hooks.chatgpt.com./cb" "https://%68ooks.chatgpt.com/cb"
                 "https://0x7f000001/cb" "https://127.0.0.1/cb" "https://[::1]/cb"
                 "https://169.254.169.254/cb" "https://worker.internal/cb"
                 "https://home.arpa/cb" "https://service.home.arpa/cb" "https://localhost/cb"
                 (str "https://hooks.chatgpt.com/" (apply str (repeat 2048 "a")))]]
    (is (nil? (webhook/canonical-url value)) (pr-str value)))
  (let [id (webhook/subscription-id "did:example:recipient" (:url callback))]
    (is (re-matches #"sub_[0-9a-f]{64}" id))
    (is (= id (webhook/subscription-id "did:example:recipient"
                                      "https://HOOKS.ChatGPT.com:443/receiver?opaque=1")))
    (is (not= id (webhook/subscription-id "did:example:other" (:url callback))))
    (is (not= id (webhook/subscription-id "did:example:recipient"
                                         "https://hooks.chatgpt.com/receiver?opaque=2")))))

(deftest secrets-are-canonical-base64-of-bounded-length
  (doseq [size [24 25 31 32 63 64]
          key [(encode-key size) (str/replace (encode-key size) #"=+$" "")]]
    (is (webhook/valid-secret? key)))
  (doseq [key [nil (encode-key 23) (encode-key 65)
               (subs (encode-key 32) 6)
               (str (encode-key 24) "=")
               (str (encode-key 32) "\n")
               (str/replace (encode-key 32) #"/" "_")
               (str/replace (encode-key 32) #"8=$" "9=")]]
    (is (not (webhook/valid-secret? key)))))

(defn- ^:async request-evidence [request opts]
  (let [headers (.-headers request)
        body (await (.text request))
        event-id (.get headers "webhook-id")
        timestamp (.get headers "webhook-timestamp")
        key (js/Buffer.from (subs (:secret callback) 6) "base64")
        signed (str event-id "." timestamp "." body)
        expected (str "v1," (-> (.createHmac crypto "sha256" key)
                                 (.update signed "utf8")
                                 (.digest "base64")))]
    (is (= (:url callback) (.-url request)))
    (is (= "POST" (.-method request)))
    (is (= "error" (.-redirect request)))
    (is (some? (.-dispatcher opts)) "the base fetch receives the SSRF-guarded dispatcher")
    (is (some? (.-signal opts)))
    (is (= "application/json" (.get headers "content-type")))
    (is (= (:id callback) (.get headers "x-mcp-subscription-id")))
    (is (re-matches #"\d{10}" timestamp))
    (is (= expected (.get headers "webhook-signature")))
    {:event-id event-id :body body :payload (js->clj (js/JSON.parse body))}))

(defn- ^:async challenge-roundtrip* []
  (let [attempts (atom [])
        fetch (webhook/guarded-fetch
               (fn ^:async challenge-callback [request opts]
                 (let [proof (await (request-evidence request opts))
                       challenge (get-in proof [:payload "challenge"])
                       [_ previous] (swap-vals! attempts conj proof)
                       echo (if (= 1 (count previous)) challenge
                              (get-in (first previous) [:payload "challenge"]))]
                   (js/Response. (js/JSON.stringify #js {:challenge echo}) #js {:status 200}))))]
    (is (= {:verified? true} (await (webhook/verify! fetch callback))))
    (is (= {:verified? false :reason "challenge_failed"}
           (await (webhook/verify! fetch callback))))
    (let [[first second] @attempts]
      (is (= "verification" (get-in first [:payload "type"])))
      (is (re-matches #"[A-Za-z0-9_-]{43}" (get-in first [:payload "challenge"])))
      (is (str/starts-with? (:event-id first) "msg_verification_"))
      (is (not= (:event-id first) (:event-id second)))
      (is (not= (:payload first) (:payload second))))))

(deftest challenge-is-signed-and-cannot-be-replayed
  (async done
    (-> (challenge-roundtrip*)
        (.catch (fn [_] (is false "challenge test rejected")))
        (.finally done))))

(defn- ^:async delivery-roundtrip* []
  (let [attempts (atom [])
        fetch (webhook/guarded-fetch
               (fn ^:async delivery-callback [request opts]
                 (let [proof (await (request-evidence request opts))
                       attempt (count (swap! attempts conj proof))]
                   (js/Response. nil #js {:status (nth [503 204 410 413] (dec attempt))}))))]
    (is (= {:accepted? false :status 503 :reason "http_error"}
           (await (webhook/deliver! fetch callback event))))
    (is (= {:accepted? true :status 204} (await (webhook/deliver! fetch callback event))))
    (is (= {:accepted? false :status 410 :reason "http_error"}
           (await (webhook/deliver! fetch callback event))))
    (is (= {:accepted? false :status 413 :reason "http_error"}
           (await (webhook/deliver! fetch callback event))))
    (is (= ["evt_fixture" "evt_fixture" "evt_fixture" "evt_fixture"]
           (mapv :event-id @attempts)))
    (is (apply = (map :body @attempts)))
    (is (every? #(= event (:payload %)) @attempts))))

(deftest delivery-signs-exact-body-and-preserves-event-identity
  (async done
    (-> (delivery-roundtrip*)
        (.catch (fn [_] (is false "delivery test rejected")))
        (.finally done))))

(defn- ^:async response-request-id* []
  (doseq [[headers expected] [[#js {"X-Request-Id" "req_71af-5"
                                    "Set-Cookie" "fixture_session=secret"}
                               {:response-request-id "req_71af-5"}]
                              [#js [#js ["x-request-id" "req_first"]
                                    #js ["x-request-id" "req_second"]] {}]
                              [#js {"x-request-id" "req_ embedded"} {}]
                              [#js {"x-request-id" (apply str (repeat 129 "a"))} {}]
                              [#js {"x-request-id" "whsec_wrong_header"} {}]
                              [#js {"x-request-id" "sk-wrong-header"} {}]
                              [#js {"x-request-id" "rk-wrong-header"} {}]]]
    (let [fetch (webhook/guarded-fetch
                  (fn [_ _]
                    (js/Promise.resolve (js/Response. "private fixture body"
                                                     #js {:status 200 :headers headers}))))]
      (is (= (merge {:accepted? true :status 200} expected)
             (await (webhook/deliver! fetch callback event)))))))

(deftest delivery-retains-only-a-valid-response-request-id
  (async done
    (-> (response-request-id*)
        (.catch (fn [_] (is false "response request ID test rejected")))
        (.finally done))))

(defn- ^:async invalid-transport* []
  (let [calls (atom 0)
        timeout-fetch (webhook/guarded-fetch
                       (fn [_ _]
                         (swap! calls inc)
                         (js/Promise.reject (js/DOMException. "hidden reason" "AbortError"))))
        large-response (webhook/guarded-fetch
                        (fn [_ _] (js/Promise.resolve
                                   (js/Response. (apply str (repeat 4097 "a"))))))
        terminal-response (webhook/guarded-fetch
                           (fn [_ _] (js/Promise.resolve
                                      (js/Response. "Gone" #js {:status 410
                                                                :headers #js {"Content-Length" "4097"
                                                                              "X-Request-Id" "req_gone"}}))))
        malformed (webhook/guarded-fetch
                   (fn [_ _] (js/Promise.resolve (js/Response. "not-json"))))]
    (is (= {:verified? false :reason "invalid_callback"}
           (await (webhook/verify! timeout-fetch (assoc callback :url "https://127.0.0.1")))))
    (is (= {:accepted? false :reason "invalid_callback"}
           (await (webhook/deliver! timeout-fetch (assoc callback :secret "invalid") event))))
    (is (zero? @calls))
    (is (= {:verified? false :reason "timeout"} (await (webhook/verify! timeout-fetch callback))))
    (is (= {:verified? false :reason "unreachable"} (await (webhook/verify! large-response callback))))
    (is (= {:verified? false :reason "challenge_failed"} (await (webhook/verify! malformed callback))))
    (is (= {:accepted? false :status 410 :reason "http_error" :response-request-id "req_gone"}
           (await (webhook/deliver! terminal-response callback event))))
    (is (= {:accepted? false :reason "payload_too_large"}
           (await (webhook/deliver! timeout-fetch callback
                                    (assoc event "data" {"tooBig" (apply str (repeat 131073 "é"))})))))
    (is (= 1 @calls))))

(deftest invalid-endpoints-payloads-and-responses-fail-closed
  (async done
    (-> (invalid-transport*)
        (.catch (fn [_] (is false "invalid transport test rejected")))
        (.finally done))))
