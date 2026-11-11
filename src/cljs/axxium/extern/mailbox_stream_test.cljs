(ns axxium.extern.mailbox-stream-test
  (:require [axxium.extern.fastify :as fastify]
            [axxium.extern.mailbox-stream :as stream]
            [clojure.string :as str]
            [cljs.test :refer [async deftest is]]
            ["node:http" :as node-http]))

(defn- connect! [url sid actor]
  (js/Promise.
   (fn [resolve reject]
     (let [req (node-http/get
                url #js {:headers #js {"mcp-session-id" sid "x-fixture-actor" actor}}
                (fn [res]
                  (let [data (atom "")
                        closed (atom false)]
                    (.setEncoding res "utf8")
                    (.on res "data" #(swap! data str %))
                    (.on res "error" (fn [_] (reset! closed true)))
                    (.on res "close" (fn [] (reset! closed true)))
                    (resolve {:status (.-statusCode res)
                              :content-type (aget (.-headers res) "content-type")
                              :data data :closed closed}))))]
       (.on req "error" reject)))))

(defn- ^:async until! [predicate]
  (loop [n 0]
    (when-not (predicate)
      (if (= n 100)
        (throw (js/Error. "Local SSE wait timed out"))
        (do
          (await (js/Promise. (fn [resolve _] (js/setTimeout resolve 5))))
          (recur (inc n)))))))

(defn- ^:async smoke! []
  (let [app (fastify/create-app)
        runtime (stream/create! app)
        authorized? (atom true)
        a "did:web:fixture.test:actors:alice"
        b "did:web:fixture.test:actors:bob"]
    (.get app "/mcp"
          (fn [req reply]
            (stream/listen! runtime req reply (aget (.-headers req) "mcp-session-id")
                            (aget (.-headers req) "x-fixture-actor")
                            (fn [] (js/Promise.resolve @authorized?)))))
    (try
      (let [url (str (await (.listen app #js {:port 0 :host "127.0.0.1"})) "/mcp")]
        (is (true? (stream/subscribe! runtime "one" a)))
        (is (false? (stream/subscribe! runtime "one" b)))
        (is (empty? (stream/subscriptions runtime a)))
        (let [one (await (connect! url "one" a))
              two (await (connect! url "two" b))]
          (is (= 200 (:status one) (:status two)))
          (is (= "text/event-stream; charset=utf-8" (:content-type one)))
          (is (= 409 (:status (await (connect! url "one" a)))))
          (is (empty? (stream/subscriptions runtime b)))
          (let [{:keys [authorize! write! heartbeat!]} (first (stream/subscriptions runtime a))]
            (is (true? (await (authorize!))))
            (is (true? (write!)))
            (await (until! #(str/includes? @(:data one) "notifications/resources/updated")))
            (is (str/includes? @(:data one) "axxium://mailbox/inbox"))
            (is (not (str/includes? @(:data two) "notifications/resources/updated")))
            (is (true? (stream/unsubscribe! runtime "one" a)))
            (is (empty? (stream/subscriptions runtime a)))
            (is (nil? (write!)) "A stale handle cannot write after unsubscribe")
            (is (not @(:closed one)) "Unsubscribe leaves the SSE connection open")
            (is (true? (stream/subscribe! runtime "one" a)))
            (reset! authorized? false)
            (await (heartbeat!))
            (await (until! #(deref (:closed one))))
            (is (empty? (stream/subscriptions runtime a))))
          (await (.close app))
          (await (until! #(deref (:closed two))))
          (is (false? (stream/subscribe! runtime "post-close" b)))))
      (finally
        (await (.close app))))))

(deftest native-inbox-stream-lifecycle
  (async done
    (-> (smoke!)
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest stream-write-failure-discards-connection-and-opt-in
  (doseq [fail-write [(constantly false) (fn [_] (throw (js/Error. "fixture write error")))]]
    (let [shutdown (atom nil)
          app #js {:addHook (fn [_ callback] (reset! shutdown callback))}
          runtime (stream/create! app)
          destroyed? (atom false)
          response #js {:writeHead (fn [& _])
                        :write fail-write
                        :once (fn [& _])
                        :destroy (fn [] (reset! destroyed? true))}
          reply #js {:hijack (fn []) :raw response}
          actor "did:web:fixture.test:actors:alice"]
      (try
        (is (stream/subscribe! runtime "one" actor))
        (stream/listen! runtime nil reply "one" actor (constantly true))
        (is (true? @destroyed?))
        (is (empty? (stream/subscriptions runtime actor)))
        (is (not (contains? (:subscriptions @runtime) "one")))
        (is (empty? (:streams @runtime)))
        (finally
          (@shutdown (fn [])))))))
