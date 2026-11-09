(ns axxium.extern.token-crypto
  "AES-256-GCM sealing for third-party refresh tokens. The key is 32 bytes
   read from a file named by config; it and plaintext tokens are never
   logged. The actor DID is bound as additional authenticated data, so a
   sealed token cannot be replayed under another actor."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            [clojure.string :as str]))

(def ^:private version "v1")

(defn- b64u [buf] (.toString buf "base64url"))
(defn- unb64u [s] (js/Buffer.from s "base64url"))

(defn key-from-base64
  "Returns a 32-byte key Buffer or nil."
  [text]
  (when (string? text)
    (let [k (js/Buffer.from (str/trim text) "base64")]
      (when (= 32 (.-length k)) k))))

(defn read-key-file [path]
  (when (and (string? path) (seq path) (fs/existsSync path))
    (key-from-base64 (fs/readFileSync path "utf8"))))

(defn read-private-key!
  "Read a small mode-0600 key owned by this process through its open descriptor."
  [path]
  (try
    (let [fd (fs/openSync path (bit-or (.-O_RDONLY fs/constants)
                                      (.-O_NOFOLLOW fs/constants)
                                      (.-O_NONBLOCK fs/constants)))]
      (try
        (let [stat (fs/fstatSync fd)
              bytes (js/Buffer.alloc 129)]
          (when (and (.isFile stat) (= 384 (bit-and (.-mode stat) 511))
                     (= (.-uid stat) (.getuid js/process)) (<= (.-size stat) 128))
            (let [length (fs/readSync fd bytes 0 129 0)]
              (when (<= length 128)
                (key-from-base64 (.toString bytes "utf8" 0 length))))))
        (finally (fs/closeSync fd))))
    (catch :default _ nil)))

(defn seal [key aad plaintext]
  (let [iv (crypto/randomBytes 12)
        cipher (crypto/createCipheriv "aes-256-gcm" key iv)
        _ (.setAAD cipher (js/Buffer.from aad "utf8"))
        ct (js/Buffer.concat #js [(.update cipher plaintext "utf8") (.final cipher)])]
    (str/join "." [version (b64u iv) (b64u (.getAuthTag cipher)) (b64u ct)])))

(defn open-sealed
  "Returns the plaintext, or nil when the key, aad or envelope do not match."
  [key aad sealed]
  (try
    (let [[v iv tag ct] (str/split sealed #"\.")]
      (when (= v version)
        (let [d (crypto/createDecipheriv "aes-256-gcm" key (unb64u iv))]
          (.setAAD d (js/Buffer.from aad "utf8"))
          (.setAuthTag d (unb64u tag))
          (str (.update d (unb64u ct) nil "utf8") (.final d "utf8")))))
    (catch :default _ nil)))
