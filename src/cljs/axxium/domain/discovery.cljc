(ns axxium.domain.discovery
  "Public names for one instance key: the did:web id, the JWKS document, and
   the did:web document.

   Objects. An origin is an absolute http(s) URL with no userinfo. did:web
   for the document served at /.well-known/did.json is the origin's host,
   with a non-default port percent-encoded and with the path, query, and
   fragment discarded. A published JWK is the image of a public Ed25519 JWK
   under the signing-key publication law.

   Morphisms.
   - did-web-id : Origin → did:web, or nil when the origin is not admissible.
   - jwks-document and did-document send a public JWK to the documents served
     at /.well-known/jwks.json and /.well-known/did.json. Both are nil when
     the JWK is not a published Ed25519 key, so private members are not in
     the image."
  (:require [axxium.law.signing :as law]
            [clojure.string :as str]))

(def ^:private did-context
  ["https://www.w3.org/ns/did/v1"
   "https://w3id.org/security/suites/jws-2020/v1"])

(defn- digit
  [ch]
  (case ch
    \0 0 \1 1 \2 2 \3 3 \4 4 \5 5 \6 6 \7 7 \8 8 \9 9
    nil))

(defn- parse-port
  "Canonical decimal port in 1..65535, or nil."
  [text]
  (when (and (string? text) (<= 1 (count text) 5))
    (loop [index 0 value 0]
      (if (= index (count text))
        (when (<= 1 value 65535) (str value))
        (if-let [d (digit (nth text index))]
          (recur (inc index) (+ (* value 10) d))
          nil)))))

(defn- default-port
  [scheme]
  (case scheme
    "https" "443"
    "http" "80"
    nil))

(defn- split-origin
  "Scheme and authority of an absolute http(s) URL, or nil."
  [origin]
  (when (string? origin)
    (when-let [[_ scheme authority]
               (re-matches #"^([A-Za-z]+)://([^/?#]+)(?:[/?#].*)?$" origin)]
      (let [scheme (str/lower-case scheme)]
        (when (#{"http" "https"} scheme)
          {:scheme scheme :authority authority})))))

(defn- host-port
  "Host and optional port. An explicit port that is not 1..65535 is refused."
  [host port-text port-present?]
  (let [host (some-> host str/lower-case)
        port (when port-present? (parse-port port-text))]
    (when (and (string? host)
               (seq host)
               (not (re-find #"\s" host))
               (or (not port-present?) (some? port)))
      {:host host :port port})))

(defn- parse-authority
  "Host and port of an authority with no userinfo."
  [authority]
  (cond
    (or (not (string? authority)) (str/includes? authority "@")) nil
    (str/starts-with? authority "[")
    (when-let [[_ host port]
               (re-matches #"^\[([0-9A-Fa-f:.]+)\](?::([0-9]{1,5}))?$" authority)]
      (host-port host port (some? port)))
    :else
    (let [colon (str/last-index-of authority ":")]
      (if (nil? colon)
        (host-port authority nil false)
        (when-not (str/includes? (subs authority 0 colon) ":")
          (host-port (subs authority 0 colon)
                     (subs authority (inc colon))
                     true))))))

(defn did-web-id
  "did:web for a document at /.well-known/did.json.
   Default ports 443 and 80 are omitted. Any other port is %3A-encoded.
   The path, query, and fragment are not part of the id. Returns nil for
   userinfo, a missing host, or a port outside 1..65535."
  [origin]
  (when-let [{:keys [scheme authority]} (split-origin origin)]
    (when-let [{:keys [host port]} (parse-authority authority)]
      (str "did:web:"
           (str/replace host ":" "%3A")
           (when (and port (not= port (default-port scheme)))
             (str "%3A" port))))))

(defn- field
  [jwk key]
  (law/jwk-value jwk key))

(defn published-jwk
  "The public Ed25519 JWK this instance advertises, or nil.
   The image has kty, crv, x, kid, use, and alg, and no private member."
  [jwk]
  (when (and (map? jwk) (not (law/contains-private-member? jwk)))
    (let [x (field jwk :x)
          kid (field jwk :kid)
          minimal {:kty (field jwk :kty) :crv (field jwk :crv) :x x :kid kid}]
      (when (and (law/public-ed25519-jwk? minimal) (law/kid? kid))
        {:kty "OKP" :crv "Ed25519" :x x :kid kid :use "sig" :alg "EdDSA"}))))

(defn jwks-document
  "JWKS document for one published key, or nil when the key is not public."
  [jwk]
  (when-let [published (published-jwk jwk)]
    {:keys [published]}))

(defn- jwk-strings
  "String keys so the did document survives a JSON encoder."
  [jwk]
  {"kty" (:kty jwk)
   "crv" (:crv jwk)
   "x" (:x jwk)
   "kid" (:kid jwk)
   "use" (:use jwk)
   "alg" (:alg jwk)})

(defn did-document
  "did:web document for origin and one published key, or nil.
   The id is did-web-id(origin). The assertion method is that id # kid."
  [origin jwk]
  (when-let [did (did-web-id origin)]
    (when-let [published (published-jwk jwk)]
      (let [method-id (str did "#" (:kid published))]
        {"@context" did-context
         "id" did
         "verificationMethod" [{"id" method-id
                                "type" "JsonWebKey2020"
                                "controller" did
                                "publicKeyJwk" (jwk-strings published)}]
         "assertionMethod" [method-id]}))))
