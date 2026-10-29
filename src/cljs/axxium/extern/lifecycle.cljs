(ns axxium.extern.lifecycle
  "Node process signals and orderly Fastify/PostgreSQL shutdown boundary.")

(defn install!
  "After the listener is ready, drain HTTP before closing its database pool.
   A stuck shutdown fails after 25 seconds; a second signal cannot start a
   competing close. The container stop grace must exceed this bound."
  [app pool]
  (let [stopping? (atom false)
        stop! (fn []
                (when (compare-and-set! stopping? false true)
                  (set! (.-exitCode js/process) 1)
                  (let [idle-sweep (js/setInterval
                                    (fn [] (.closeIdleConnections (.-server app)))
                                    100)
                        deadline (js/setTimeout
                                  (fn []
                                    (js/console.error "Axxium shutdown timed out")
                                    (js/process.exit 1))
                                  25000)]
                    ;; Keep the deadline alive until process exit without
                    ;; making an otherwise clean shutdown wait for it.
                    (.unref deadline)
                    (-> (.close app)
                        (.then (fn [] (.end pool)))
                        (.then (fn []
                                 (js/clearInterval idle-sweep)
                                 (set! (.-exitCode js/process) 0)))
                        (.catch (fn [_error]
                                  (js/clearInterval idle-sweep)
                                  (js/clearTimeout deadline)
                                  (js/console.error "Axxium shutdown failed")
                                  (js/process.exit 1)))))))]
    (.on js/process "SIGTERM" stop!)
    (.on js/process "SIGINT" stop!)
    nil))
