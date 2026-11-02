(ns axxium.law.signing
  "Admission laws for this instance's signing key and its public documents.

   Objects. A kid is an unpadded base64url token of length 1–128. A public
   Ed25519 JWK has kty OKP, crv Ed25519, and a 32-byte x, and no private
   member. Private members are d, p, q, dp, dq, qi, k, and oth. A published
   JWK is a public Ed25519 JWK with alg EdDSA, use sig, and a kid. JWKS is a
   key set of published JWKs. A did:web document names one assertion method
   whose public key is that JWK.

   Laws.
   - Kid admission. kid-admitted?(header, instance) holds exactly when both
     are kids and header = instance.
   - Public material. public-ed25519-jwk? holds exactly when the JWK is an
     Ed25519 public key and contains-private-member? is false.
   - Publication. A JWKS or did:web document is admitted only when every
     nested value is free of private members and each advertised key is a
     published JWK. Builders return nil for anything else."
  (:require [clojure.string :as str]))

(def ^:private private-member-names
  #{"d" "p" "q" "dp" "dq" "qi" "k" "oth"})

(def ^:private kid-pattern #"[A-Za-z0-9_-]+")

(def ^:private x-pattern #"[A-Za-z0-9_-]{43}")

(defn jwk-value
  "Value of a JWK member, whether the key is a keyword or its name.
   A string key is used as itself, which is how @context is stored."
  [jwk key]
  (when (map? jwk)
    (let [named (cond
                  (keyword? key) (name key)
                  (string? key) key)]
      (cond
        (and (keyword? key) (contains? jwk key)) (get jwk key)
        (and (some? named) (contains? jwk named)) (get jwk named)))))

(defn kid?
  "A key id: 1–128 unpadded base64url characters."
  [value]
  (and (string? value)
       (<= 1 (count value) 128)
       (= value (re-matches kid-pattern value))))

(defn- private-member-name?
  "True when key names a private JWK member."
  [key]
  (contains? private-member-names
             (cond
               (keyword? key) (name key)
               (string? key) key
               :else nil)))

(defn contains-private-member?
  "True when a private JWK member occurs anywhere in value."
  [value]
  (boolean
   (cond
     (map? value) (or (some private-member-name? (keys value))
                      (some contains-private-member? (vals value)))
     (sequential? value) (some contains-private-member? value)
     :else false)))

(defn public-ed25519-jwk?
  "Public-material law for an Ed25519 JWK. x is the 32-byte public key."
  [jwk]
  (and (map? jwk)
       (= "OKP" (jwk-value jwk :kty))
       (= "Ed25519" (jwk-value jwk :crv))
       (let [x (jwk-value jwk :x)
             kid (jwk-value jwk :kid)]
         (and (string? x)
              (= x (re-matches x-pattern x))
              (or (nil? kid) (kid? kid))))
       (not (contains-private-member? jwk))))

(defn published-jwk?
  "A public Ed25519 JWK advertised with alg EdDSA, use sig, and a kid."
  [jwk]
  (and (public-ed25519-jwk? jwk)
       (kid? (jwk-value jwk :kid))
       (= "sig" (jwk-value jwk :use))
       (= "EdDSA" (jwk-value jwk :alg))))

(defn kid-admitted?
  "Kid-admission law: the header kid equals this instance's kid."
  [header-kid instance-kid]
  (and (kid? instance-kid)
       (= header-kid instance-kid)))

(defn jwks-document?
  "Publication law for a JWKS document: one published key, nothing private."
  [document]
  (let [key-set (jwk-value document :keys)]
    (and (map? document)
         (not (contains-private-member? document))
         (sequential? key-set)
         (= 1 (count key-set))
         (published-jwk? (first key-set)))))

(defn did-document?
  "Publication law for a did:web document at an origin's /.well-known/did.json.
   The assertion method is did#kid and its public key is a published JWK."
  [document]
  (let [id (jwk-value document :id)
        methods (jwk-value document :verificationMethod)
        assertion (jwk-value document :assertionMethod)
        method (when (sequential? methods) (first methods))
        jwk (jwk-value method :publicKeyJwk)
        kid (jwk-value jwk :kid)
        method-id (jwk-value method :id)]
    (and (map? document)
         (not (contains-private-member? document))
         (= (jwk-value document "@context")
            ["https://www.w3.org/ns/did/v1"
             "https://w3id.org/security/suites/jws-2020/v1"])
         (string? id)
         (str/starts-with? id "did:web:")
         (sequential? methods)
         (= 1 (count methods))
         (published-jwk? jwk)
         (= (jwk-value method :type) "JsonWebKey2020")
         (= (jwk-value method :controller) id)
         (= method-id (str id "#" kid))
         (= assertion [method-id]))))
