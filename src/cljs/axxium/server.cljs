(ns axxium.server
  "Axxium HTTP server.
   Fastify-based, serving the identity provider API and portal."
  (:require [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.extern.http :as http]
            [axxium.routes.auth :as auth-routes]
            [axxium.routes.actor :as actor-routes]
            [axxium.routes.agents :as agent-routes]
            [axxium.routes.atproto :as atproto-routes]
            [axxium.routes.atproto-oauth :as atproto-oauth-routes]
            [axxium.routes.google :as google-routes]
            [axxium.routes.health :as health-routes]
            ["fastify" :default Fastify]
            ["@fastify/cors" :default fastifyCors]
            ["@fastify/cookie" :default fastifyCookie]
            ["@fastify/static" :default fastifyStatic]
            ["node:path" :as path]))

(defn- create-app
  "Create and configure the Fastify application."
  []
  (let [app (Fastify #js {:logger #js {:redact #js ["req.url"]}})]
    (.register app fastifyCors
               #js {:origin (cfg/get-in-config [:axxium/public-base-url])
                    :credentials true
                    :methods #js ["GET" "POST" "PUT" "DELETE" "OPTIONS"]
                    :allowedHeaders #js ["Authorization" "Content-Type" "X-Requested-With"]})
    (.register app fastifyCookie)
    app))

(defn- register-routes!
  "Register all API routes on the app."
  [app]
  (health-routes/register-health-routes! app)
  (auth-routes/register-auth-routes! app)
  (google-routes/register-google-routes! app)
  (actor-routes/register-actor-routes! app)
  (agent-routes/register-agent-routes! app)
  (atproto-routes/register-atproto-routes! app)
  (atproto-oauth-routes/register-atproto-oauth-routes! app))

(defn- register-static!
  "Register static file serving for the portal."
  [app]
  (-> (.register app fastifyStatic
                  #js {:root (.resolve path "resources" "public")
                       :prefix "/portal/"})))

(defn start!
  "Start the Axxium server.
   Initializes database schema and starts listening."
  []
  (println "Starting Axxium identity kernel...")
  (-> (db/init-schema!)
      (.then
        (fn [_]
          (println "Database schema initialized")
          (let [app (create-app)]
            (http/register-portal-origin!
             app (cfg/get-in-config [:axxium/public-base-url]))
            (register-routes! app)
            (register-static! app)
            (-> (.listen app #js {:port (cfg/get-in-config [:axxium/port])
                                  :host (cfg/get-in-config [:axxium/host])})
                (.then
                  (fn [address]
                    (println (str "Axxium listening on " address))
                    (println (str "Portal: " (cfg/get-in-config [:axxium/public-base-url]) "/portal/index.html"))
                    app))))))
      (.catch
        (fn [err]
          (println (str "Failed to start Axxium: " (.-message err)))
          (js/process.exit 1)))))
