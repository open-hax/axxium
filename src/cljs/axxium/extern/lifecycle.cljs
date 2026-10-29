(ns axxium.extern.lifecycle
  "Node process signals and orderly Fastify/PostgreSQL shutdown boundary.")

(defn stopping? [controller]
  @(:stopping? controller))

(defn attach!
  "Attach the app synchronously before any asynchronous app initialization."
  [controller app]
  (when (stopping? controller)
    (throw (js/Error. "Axxium is stopping")))
  (reset! (:app controller) app)
  nil)

(defn close-startup!
  "Close the schema pool exactly once, including when startup races shutdown."
  [controller]
  (or @(:startup-close controller)
      (let [closing (-> (js/Promise.resolve)
                        (.then (fn [] (.end (:startup-pool controller))))
                        (.then (fn [] (reset! (:startup-closed? controller) true))))]
        (reset! (:startup-close controller) closing)
        closing)))

(defn shutdown-bound-ms [startup-closed? statement-timeout-ms]
  (if startup-closed? 25000 (max 25000 (+ statement-timeout-ms 5000))))

(defn install!
  "Install before database initialization; attach the app before async setup.
   Drain HTTP if present, then close both pools. A stuck shutdown fails after
   max(25 seconds, the unfinished schema statement limit plus 5 seconds);
   repeated signals cannot start a competing close."
  [pool startup-pool statement-timeout-ms]
  (let [controller {:stopping? (atom false) :app (atom nil)
                    :startup-pool startup-pool :startup-close (atom nil)
                    :startup-closed? (atom false)}
        stop! (fn []
                (when (compare-and-set! (:stopping? controller) false true)
                  (set! (.-exitCode js/process) 1)
                  (let [app @(:app controller)
                        idle-sweep (when app
                                     (js/setInterval
                                      (fn [] (.closeIdleConnections (.-server app)))
                                      100))
                        bound-ms (shutdown-bound-ms @(:startup-closed? controller)
                                                    statement-timeout-ms)
                        deadline (js/setTimeout
                                  (fn []
                                    (js/console.error "Axxium shutdown timed out")
                                    (js/process.exit 1))
                                  bound-ms)]
                    ;; Keep the deadline alive until process exit without
                    ;; making an otherwise clean shutdown wait for it.
                    (.unref deadline)
                    (-> (js/Promise.resolve)
                        (.then (fn [] (when app (.close app))))
                        (.then (fn [] (close-startup! controller)))
                        (.then (fn [] (.end pool)))
                        (.then (fn []
                                 (when idle-sweep (js/clearInterval idle-sweep))
                                 (set! (.-exitCode js/process) 0)))
                        (.catch (fn [_error]
                                  (when idle-sweep (js/clearInterval idle-sweep))
                                  (js/clearTimeout deadline)
                                  (js/console.error "Axxium shutdown failed")
                                  (js/process.exit 1)))))))]
    (.on js/process "SIGTERM" stop!)
    (.on js/process "SIGINT" stop!)
    controller))
