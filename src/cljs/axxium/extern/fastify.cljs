(ns axxium.extern.fastify
  "Fastify application construction and logging boundary."
  (:require ["fastify" :default Fastify]))

(defn create-app
  "Return an opaque Fastify handle with request URLs redacted from logs.
   `trust-proxy` (optional) is Fastify's trustProxy option."
  ([] (create-app nil))
  ([trust-proxy]
   (let [opts #js {:logger #js {:redact #js ["req.url"]}}]
     (when (some? trust-proxy) (aset opts "trustProxy" trust-proxy))
     (Fastify opts))))
