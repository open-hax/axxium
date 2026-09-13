(ns axxium.extern.http
  "Fastify transport adapter: raw requests, replies, cookies and static files stay here."
  (:require ["fastify" :default fastify]
            ["@fastify/cookie" :default cookie]
            ["@fastify/rate-limit" :default rate-limit]
            ["node:fs" :as fs]
            ["node:path" :as path]))
(defn ^:async create! "Create an HTTP server without logging request credentials." [public-url]
  (let [app (fastify #js {:logger false :bodyLimit 32768})]
    (await (.register app cookie))
    (await (.register app rate-limit #js {:global false :max 30 :timeWindow 60000 :cache 5000}))
    (.addHook app "onRequest"
      (^:async fn [req reply]
        (let [origin (aget (.-headers req) "origin")]
          (when (and (not (contains? #{"GET" "HEAD" "OPTIONS"} (.-method req)))
                     origin (not= origin public-url))
            (.send (.code reply 403) #js {:error "Cross-origin write rejected"})))))
    app))
(defn- request-data [req]
  {:body (js->clj (or (.-body req) #js {}) :keywordize-keys true)
   :headers (js->clj (.-headers req) :keywordize-keys true)
   :cookies (js->clj (js/Object.assign #js {} (or (.-cookies req) #js {})) :keywordize-keys true)
   :params (js->clj (js/Object.assign #js {} (or (.-params req) #js {})) :keywordize-keys true)})
(defn route! "Register a CLJS-data handler with sanitized errors and same-host session cookies." [app method url handler config]
  (.route app
    (clj->js {:method method :url url
      :config {:rateLimit (when (= method "POST") {:max 30 :timeWindow 60000})}
      :handler
      (^:async fn [req reply]
        (try
          (let [{:keys [status body session-token clear-session?]} (await (handler (request-data req)))
                cookie-name (:session/cookie-name config)
                options #js {:path "/" :httpOnly true :sameSite "lax"
                             :secure (:session/cookie-secure config)
                             :maxAge (* 3600 (:jwt/expiry-hours config))}]
            (.header reply "Cache-Control" "no-store")
            (when session-token (.setCookie reply cookie-name session-token options))
            (when clear-session? (.clearCookie reply cookie-name #js {:path "/"}))
            (.send (.code reply (or status 200)) (clj->js body)))
          (catch :default error
            (let [data (ex-data error)
                  conflict? (= "23505" (.-code error))
                  status (or (:status data) (when conflict? 409) 500)
                  message (cond conflict? "Account or transfer already registered"
                                (< status 500) (.-message error)
                                :else "Identity operation failed")]
              (.send (.code reply status) (clj->js {:error message :code (or (:code data) "identity_error")}))))))})))
(defn ^:async serve-static! "Serve only the three fixed portal assets, with no filesystem path from a request." [app]
  (doseq [[file content-type] [["index.html" "text/html; charset=utf-8"]
                              ["portal.css" "text/css; charset=utf-8"]
                              ["portal.js" "text/javascript; charset=utf-8"]]]
    (let [content (.readFileSync fs (.resolve path "resources" "public" file))]
      (.get app (str "/portal/" file)
        (fn [_ reply]
          (.header reply "Content-Security-Policy" "default-src 'self'; script-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
          (.header reply "X-Content-Type-Options" "nosniff")
          (.header reply "Referrer-Policy" "no-referrer")
          (.header reply "Cache-Control" "no-store")
          (.send (.type reply content-type) content)))))
  (.get app "/" (fn [_ reply] (.redirect reply "/portal/index.html"))))
(defn listen! "Bind the configured host/port." [app host port]
  (.listen app #js {:host host :port port}))
