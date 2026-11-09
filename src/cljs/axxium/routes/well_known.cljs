(ns axxium.routes.well-known
  "Public signing-key discovery.
   GET /.well-known/jwks.json and GET /.well-known/did.json serve only
   documents the signing-key publication law admits. A refusal is a fixed
   500 and does not include key material."
  (:require [axxium.config :as cfg]
            [axxium.domain.discovery :as discovery]
            [axxium.extern.http :as http]
            [axxium.extern.signing-key :as signing-key]
            [axxium.law.signing :as law]))

(def ^:private unavailable
  {:error "Signing key is not available"})

(defn- respond!
  [reply document admitted?]
  (if (and (some? document) (admitted? document))
    (do
      (http/set-header! reply "Cache-Control" "public, max-age=300")
      (http/send! reply 200 document))
    (http/send! reply 500 unavailable)))

(defn- jwks!
  [_req reply]
  (try
    (respond! reply (discovery/jwks-document (signing-key/public-jwk)) law/jwks-document?)
    (catch :default _
      (http/send! reply 500 unavailable))))

(defn- did!
  [_req reply]
  (try
    (respond! reply
              (discovery/did-document
               (cfg/get-in-config [:axxium/public-base-url])
               (signing-key/public-jwk))
              law/did-document?)
    (catch :default _
      (http/send! reply 500 unavailable))))

(defn register-well-known-routes!
  "Register the public JWKS and did:web routes."
  [app]
  (http/get! app "/.well-known/jwks.json" jwks!)
  (http/get! app "/.well-known/did.json" did!))
