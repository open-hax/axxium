(ns axxium.extern.signing-key
  "This instance's Ed25519 signing key.
   The private handle stays in memory. load! and install! return only {:kid}.
   A failure is an ex-info whose data is {:reason keyword} and whose message
   is fixed, so PEM text and private JWK members are not attached."
  (:require [axxium.config :as cfg]
            [axxium.law.signing :as law]
            [clojure.string :as str]
            ["jose" :as jose]
            ["node:fs" :as fs]))

(def ^:private material
  (atom nil))

(defn- reject!
  [reason]
  (throw (ex-info "Signing key is not loaded" {:reason reason})))

(defn- pem?
  [text]
  (and (string? text)
       (str/includes? text "-----BEGIN ")
       (str/includes? text "PRIVATE KEY")))

(defn- minimal-public
  "Required RFC 7638 members of a public Ed25519 JWK, or nil."
  [jwk]
  (let [public (dissoc jwk :d :p :q :dp :dq :qi :k :oth)]
    (when (law/public-ed25519-jwk? public)
      {:kty "OKP" :crv "Ed25519" :x (:x public)})))

(defn- read-pem!
  [path]
  (when-not (and (string? path) (seq path))
    (reject! :missing-path))
  (try
    (.readFileSync fs path "utf8")
    (catch :default _
      (reject! :unreadable))))

(defn- ^:async import-private
  [pem]
  (try
    (await (jose/importPKCS8 pem "EdDSA"))
    (catch :default _
      (reject! :invalid-key))))

(defn- ^:async exported-minimal
  [private]
  (let [exported (try
                   (js->clj (await (jose/exportJWK private)) :keywordize-keys true)
                   (catch :default _
                     (reject! :invalid-key)))]
    (or (minimal-public exported) (reject! :not-public))))

(defn- ^:async thumbprint
  [minimal]
  (try
    (await (jose/calculateJwkThumbprint (clj->js minimal) "sha256"))
    (catch :default _
      (reject! :invalid-key))))

(defn- admit-kid!
  [configured thumb]
  (let [kid (or configured thumb)]
    (when-not (law/kid? kid)
      (reject! :invalid-kid))
    kid))

(defn- published
  [minimal kid]
  {:kty "OKP" :crv "Ed25519" :x (:x minimal) :kid kid :use "sig" :alg "EdDSA"})

(defn- ^:async import-public
  [minimal]
  (try
    (await (jose/importJWK (clj->js minimal) "EdDSA"))
    (catch :default _
      (reject! :invalid-key))))

(defn- key-handle?
  "A jose KeyObject. cljs.core/object? is only true for plain Objects."
  [value]
  (instance? js/Object value))

(defn- store!
  [loaded]
  (let [public (:public-jwk loaded)
        kid (:kid loaded)]
    (when-not (and (law/published-jwk? public)
                   (key-handle? (:private-key loaded))
                   (key-handle? (:public-key loaded))
                   (= kid (law/jwk-value public :kid)))
      (reject! :not-public))
    (reset! material (select-keys loaded [:private-key :public-key :public-jwk :kid]))
    {:kid kid}))

(defn- ^:async material-from-private
  [private configured-kid]
  (let [minimal (await (exported-minimal private))
        kid (admit-kid! configured-kid (await (thumbprint minimal)))
        public-jwk (published minimal kid)
        public-key (await (import-public minimal))]
    {:private-key private
     :public-key public-key
     :public-jwk public-jwk
     :kid kid}))

(defn current!
  "In-memory signing material, or a :not-loaded failure."
  []
  (or @material (reject! :not-loaded)))

(defn public-jwk
  "The published public JWK, or a :not-loaded failure."
  []
  (:public-jwk (current!)))

(defn unload!
  "Drop the in-memory key. Does not read or write the key file."
  []
  (reset! material nil))

(defn install!
  "Install already-built public material. Returns {:kid}."
  [loaded]
  (store! loaded))

(defn ^:async generate-ephemeral!
  "An Ed25519 key that is not installed and is not a production fallback.
   The kid is the SHA-256 JWK thumbprint of {kty, crv, x}."
  []
  (let [pair (await (jose/generateKeyPair "EdDSA" #js {:crv "Ed25519"}))]
    (await (material-from-private (.-privateKey pair) nil))))

(defn ^:async load!
  "Read AXXIUM_SIGNING_KEY_FILE, install the key, and return {:kid}.
   AXXIUM_SIGNING_KID overrides the thumbprint when it is a kid.
   The path and kid are read before any await. The PEM is not retained in
   the result or the failure."
  []
  (let [path (cfg/get-in-config [:signing/key-file])
        configured-kid (cfg/get-in-config [:signing/kid])
        pem (read-pem! path)]
    (when-not (pem? pem)
      (reject! :invalid-pem))
    (store! (await (material-from-private
                    (await (import-private pem))
                    configured-kid)))))
