(ns axxium.extern.fastify
  "Fastify application construction and logging boundary."
  (:require ["fastify" :default Fastify]))

(defn create-app
  "Return an opaque Fastify handle with request URLs redacted from logs."
  []
  (Fastify #js {:logger #js {:redact #js ["req.url"]}}))
