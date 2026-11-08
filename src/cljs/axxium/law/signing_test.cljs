(ns axxium.law.signing-test
  "Properties and examples for kid admission, public Ed25519 keys, and did:web."
  (:require [axxium.domain.discovery :as discovery]
            [axxium.law.signing :as law]
            [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private b64
  (vec "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"))

(def ^:private alphabet
  (vec "abcdefghijklmnopqrstuvwxyz0123456789"))

(defn- draw [alphabet-vec n]
  (gen/fmap #(apply str %) (gen/vector (gen/elements alphabet-vec) n)))

(def ^:private gen-x
  (draw b64 43))

(def ^:private gen-kid
  (gen/fmap #(apply str %) (gen/vector (gen/elements b64) 1 32)))

(def ^:private gen-kid-any
  (gen/one-of
   [gen-kid
    (gen/elements [nil "" " " "a+b" "has space" 7])
    (gen/return (apply str (repeat 129 "a")))]))

(def ^:private gen-label
  (gen/let [head (gen/elements alphabet)
            tail (gen/vector (gen/elements alphabet) 0 8)]
    (apply str head tail)))

(def ^:private gen-host
  (gen/let [labels (gen/vector gen-label 1 3)]
    (str/join "." labels)))

(def ^:private gen-suffix
  (gen/one-of
   [(gen/return "")
    (gen/return "/")
    (gen/fmap #(str "/" %) gen-label)
    (gen/fmap #(str "/" % "?q=1") gen-label)
    (gen/fmap #(str "/.well-known/did.json#" %) gen-label)]))

(def ^:private gen-other-port
  (gen/fmap (fn [n] (if (= n 443) 8443 n)) (gen/choose 1 65535)))

(defn- published [x kid]
  {:kty "OKP" :crv "Ed25519" :x x :kid kid :use "sig" :alg "EdDSA"})

(defspec kid-admission-is-equality
  40
  (prop/for-all [header gen-kid-any instance gen-kid-any]
    (= (law/kid-admitted? header instance)
       (and (law/kid? instance) (= header instance)))))

(defspec private-members-are-not-published
  40
  (prop/for-all [x gen-x
                 kid gen-kid
                 member (gen/elements [:d :p :q :dp :dq :qi :k :oth])]
    (let [public (published x kid)
          tainted (assoc public member "hidden")
          jwks (discovery/jwks-document public)
          did (discovery/did-document "https://axxium.promethean.rest" public)]
      (and (law/published-jwk? public)
           (not (law/public-ed25519-jwk? tainted))
           (nil? (discovery/jwks-document tainted))
           (nil? (discovery/did-document "https://axxium.promethean.rest" tainted))
           (law/jwks-document? jwks)
           (law/did-document? did)
           (not (law/contains-private-member? jwks))
           (not (law/contains-private-member? did))
           (= kid (get-in jwks [:keys 0 :kid]))
           (law/kid-admitted? kid (get-in jwks [:keys 0 :kid]))))))

(defspec path-query-and-fragment-are-not-in-the-did
  40
  (prop/for-all [host gen-host suffix gen-suffix]
    (= (discovery/did-web-id (str "https://" host))
       (discovery/did-web-id (str "https://" host suffix)))))

(defspec https-default-port-is-omitted
  40
  (prop/for-all [host gen-host]
    (= (str "did:web:" host)
       (discovery/did-web-id (str "https://" host))
       (discovery/did-web-id (str "https://" host ":443"))
       (discovery/did-web-id (str "https://" host ":0443/portal"))
       (discovery/did-web-id (str "HTTPS://" (str/upper-case host) "/.well-known/did.json")))))

(defspec http-default-port-is-omitted
  20
  (prop/for-all [host gen-host]
    (= (str "did:web:" host)
       (discovery/did-web-id (str "http://" host))
       (discovery/did-web-id (str "http://" host ":80")))))

(defspec a-non-default-port-is-percent-encoded
  40
  (prop/for-all [host gen-host port gen-other-port]
    (and (= (str "did:web:" host "%3A" port)
            (discovery/did-web-id (str "https://" host ":" port)))
         (not= (discovery/did-web-id (str "https://" host ":" port))
               (discovery/did-web-id (str "https://" host))))))

(defspec userinfo-is-rejected
  20
  (prop/for-all [host gen-host user gen-label]
    (and (nil? (discovery/did-web-id (str "https://" user "@" host)))
         (nil? (discovery/did-web-id (str "https://" user ":hidden@" host))))))

(deftest did-web-examples
  (is (= "did:web:axxium.promethean.rest"
         (discovery/did-web-id "https://axxium.promethean.rest")))
  (is (= "did:web:axxium.promethean.rest"
         (discovery/did-web-id "https://axxium.promethean.rest:443/ignored?q=1#f")))
  (is (= "did:web:127.0.0.1%3A8787"
         (discovery/did-web-id "http://127.0.0.1:8787")))
  (is (= "did:web:host%3A8443"
         (discovery/did-web-id "https://host:8443/path?q#f")))
  (is (= "did:web:2001%3Adb8%3A%3A1"
         (discovery/did-web-id "https://[2001:DB8::1]:443/path")))
  (is (= "did:web:%3A%3A1%3A8787"
         (discovery/did-web-id "https://[::1]:8787")))
  (is (nil? (discovery/did-web-id "https://user:hidden@127.0.0.1:8787")))
  (is (nil? (discovery/did-web-id "https://host:65536")))
  (is (nil? (discovery/did-web-id "https://host:0")))
  (is (nil? (discovery/did-web-id nil)))
  (is (not (law/kid? (apply str (repeat 129 "a")))))
  (is (law/kid? (apply str (repeat 43 "A"))))
  (let [kid (apply str (repeat 43 "B"))
        jwk (published (apply str (repeat 43 "A")) kid)
        did (discovery/did-document "http://127.0.0.1:8787" jwk)]
    (is (law/did-document? did))
    (is (= "did:web:127.0.0.1%3A8787" (get did "id")))
    (is (= (str "did:web:127.0.0.1%3A8787#" kid)
           (get-in did ["assertionMethod" 0])))
    (is (law/contains-private-member?
         {"verificationMethod" [{"publicKeyJwk" {"d" "hidden"}}]}))
    (is (not (law/did-document?
              {"verificationMethod" [{"publicKeyJwk" {"d" "hidden"}}]})))))
