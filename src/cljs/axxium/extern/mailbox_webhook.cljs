(ns axxium.extern.mailbox-webhook
  "Bounded, signed outbound delivery for mailbox event callbacks."
  (:require [axxium.extern.agent-credentials :as credentials]
            [axxium.law.mailbox-webhook :as law]
            [clojure.string :as str]
            ["@atproto-labs/fetch-node" :refer [safeFetchWrap]]
            ["node:crypto" :as crypto]))

(def ^:private callback-limit 2048)
(def ^:private response-limit 4096)
(def ^:private event-limit (* 256 1024))
(def ^:private timeout-ms 5000)
(def ^:private reserved-suffixes
  #{"example" "internal" "invalid" "local" "localdomain" "localhost" "onion" "test"})

(defn- public-hostname? [hostname]
  (let [labels (str/split hostname #"\." -1)
        suffix (last labels)]
    (and (<= 2 (count labels))
         (<= (count hostname) 253)
         (re-find #"[a-z]" suffix)
         (not (contains? reserved-suffixes suffix))
         (not (or (= hostname "home.arpa") (str/ends-with? hostname ".home.arpa")))
         (every? #(re-matches #"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?" %) labels))))

(defn canonical-url
  "Return a bounded canonical HTTPS URL with a DNS hostname, or nil.
   The guarded transport checks resolved addresses when opening the connection."
  [value]
  (when (and (string? value)
             (<= (count value) callback-limit)
             (not (re-find #"[\s\u0000-\u001f\u007f\\#]" value)))
    (when-let [[_ authority] (re-find #"^https://([^/?]+)" value)]
      (when (re-matches #"[A-Za-z0-9.-]+(?::443)?" authority)
        (try
          (let [url (js/URL. value)]
            (when (and (public-hostname? (.-hostname url))
                       (<= (count (.-href url)) callback-limit))
              (.-href url)))
          (catch :default _ nil))))))

(defn- secret-bytes [value]
  (when (and (string? value) (<= (count value) 94))
    (when-let [[_ encoded] (re-matches #"whsec_([A-Za-z0-9+/]{32,86}={0,2})" value)]
      (let [key (js/Buffer.from encoded "base64")
            canonical (.toString key "base64")]
        (when (and (<= 24 (.-length key) 64)
                   (or (= encoded canonical)
                       (= encoded (str/replace canonical #"=+$" ""))))
          key)))))

(defn valid-secret?
  "Admit canonical padded or unpadded Standard Webhooks keys of 24–64 bytes."
  [value]
  (some? (secret-bytes value)))

(defn subscription-id
  "Derive the stable identity for the unfiltered message.available event."
  [owner url]
  (when (and (string? owner) (not (str/blank? owner)))
    (when-let [callback (canonical-url url)]
      (str "sub_" (credentials/token-hash
                   (js/JSON.stringify #js [owner callback "message.available" #js {}]))))))

(defn guarded-fetch
  "Create the outbound callback transport. A test may inject its base fetch."
  ([] (guarded-fetch js/fetch))
  ([fetch]
   (safeFetchWrap #js {:fetch fetch
                      :allowIpHost false
                      :timeout timeout-ms
                      :responseMaxSize response-limit})))

(def ^:private fetch-callback (delay (guarded-fetch)))

(defn- header-id? [value]
  (and (string? value) (some? (re-matches #"[A-Za-z0-9_-]{1,128}" value))))

(defn- request-options [subscription-id event-id key body signal]
  (let [timestamp (str (js/Math.floor (/ (credentials/now-ms) 1000)))
        signature (-> (.createHmac crypto "sha256" key)
                      (.update (str event-id "." timestamp ".") "utf8")
                      (.update body)
                      (.digest "base64"))]
    #js {:method "POST"
         :redirect "error"
         :signal signal
         :headers #js {"Content-Type" "application/json"
                       "webhook-id" event-id
                       "webhook-timestamp" timestamp
                       "webhook-signature" (str "v1," signature)
                       "X-MCP-Subscription-Id" subscription-id}
         :body body}))

(defn- cancel-body! [response]
  (when-let [body (.-body response)]
    ;; The status suffices for delivery. Do not read an unrelated response body.
    (.catch (.cancel body) (fn [_] nil))))

(defn- with-response-request-id [result response]
  (let [request-id (some-> response .-headers (.get "x-request-id"))]
    (cond-> result
      (law/response-request-id? request-id) (assoc :response-request-id request-id))))

(defn- transport-failure [signal error]
  (let [response (some-> error (aget "response"))
        status (some-> response (aget "status"))
        name (some-> error (aget "name"))]
    (cond
      ;; Preserve terminal statuses even if safeFetchWrap rejects the response size.
      (contains? #{410 413} status)
      (with-response-request-id {:status status :reason "http_error"} response)
      (or (.-aborted signal) (contains? #{"AbortError" "TimeoutError"} name)) {:reason "timeout"}
      :else {:reason "unreachable"})))

(defn- ^:async post! [fetch {:keys [id url secret]} event-id payload response->result]
  (let [url (canonical-url url)
        key (secret-bytes secret)]
    (if-not (and url key (header-id? id) (header-id? event-id))
      {:reason "invalid_callback"}
      (let [signal (js/AbortSignal.timeout timeout-ms)]
        (try
          (let [body (js/Buffer.from (js/JSON.stringify (clj->js payload)) "utf8")]
            (if (> (.-length body) event-limit)
              {:reason "payload_too_large"}
              (let [send (or fetch @fetch-callback)
                    response (await (send url (request-options id event-id key body signal)))]
                (await (response->result response)))))
          (catch :default error
            (transport-failure signal error)))))))

(defn- challenge-match? [expected actual]
  (and (string? actual)
       (re-matches #"[A-Za-z0-9_-]{43}" actual)
       (crypto/timingSafeEqual (js/Buffer.from expected "ascii")
                               (js/Buffer.from actual "ascii"))))

(defn ^:async verify!
  "Verify a callback with a fresh, request-local challenge. Return only safe results."
  ([subscription] (await (verify! nil subscription)))
  ([fetch subscription]
   (let [challenge (.toString (crypto/randomBytes 32) "base64url")
         event-id (str "msg_verification_" (credentials/secure-id))
         result (await (post! fetch subscription event-id
                              {"type" "verification" "challenge" challenge}
                              (fn ^:async verify-response [response]
                                (if (.-ok response)
                                  (let [payload (try (await (.json response))
                                                     (catch :default error
                                                       (if (instance? js/SyntaxError error) nil
                                                         (throw error))))]
                                    (if (challenge-match? challenge (some-> payload (aget "challenge")))
                                      {:verified? true}
                                      {:reason "challenge_failed"}))
                                  (do (cancel-body! response)
                                      {:reason "challenge_failed"})))))]
     (merge {:verified? false} (select-keys result [:verified? :reason])))))

(defn ^:async deliver!
  "Sign and send one event. The caller owns event identity and retry policy."
  ([subscription event] (await (deliver! nil subscription event)))
  ([fetch subscription event]
   (let [result (await (post! fetch subscription (get event "eventId") event
                              (fn [response]
                                (cancel-body! response)
                                (with-response-request-id
                                  (cond-> {:accepted? (.-ok response) :status (.-status response)}
                                    (not (.-ok response)) (assoc :reason "http_error"))
                                  response))))]
     (merge {:accepted? false} result))))
