(ns axxium.routes.health
  "Health check and system routes."
  (:require [axxium.db :as db]
            [axxium.extern.http :as http]))

(defn register-health-routes!
  "Register health and system routes."
  [app]
  ;; GET /health — Service health
  (http/get! app "/health"
    (fn [_req reply]
      (-> (db/query-sql (db/q-health-check))
          (.then
            (fn [_]
              (http/send! reply 200 {:status "ok"
                                     :service "axxium"
                                     :version "0.1.0"})))
          (.catch
            (fn [err]
              (http/send! reply 503 {:status "error"
                                     :service "axxium"
                                     :error (http/error-message err)}))))))

  ;; GET / — Redirect to portal
  (http/get! app "/"
    (fn [_req reply]
      (http/redirect! reply "/portal/index.html")))
)
