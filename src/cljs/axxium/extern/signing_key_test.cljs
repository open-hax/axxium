(ns axxium.extern.signing-key-test
  "Ed25519 load, kid admission, and publication. Failures carry a reason only."
  (:require [axxium.config :as cfg]
            [axxium.domain.discovery :as discovery]
            [axxium.extern.jose :as jose]
            [axxium.extern.signing-key :as signing-key]
            [axxium.law.signing :as law]
            [cljs.test :refer [async deftest is]]
            [clojure.string :as str]
            ["jose" :as jose-lib]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(defn- safe-error
  "A failure report that drops any message carrying key material."
  [error]
  (let [message (str (ex-message error) " " (:reason (ex-data error)))]
    (if (or (str/includes? message "PRIVATE")
            (str/includes? message "BEGIN"))
      "error contained key material and was withheld"
      message)))

(defn- reason
  [error]
  (:reason (ex-data error)))

(defn- ^:async sign-and-open
  [material]
  (let [token (await (jose/sign-actor!
                      {:sub "actor_1"}
                      {:private-key (:private-key material)
                       :kid (:kid material)
                       :issuer "axxium"
                       :audience "promethean+development"
                       :expiry-hours 1}))]
    (await (jose/verify-actor!
            token
            {:public-key (:public-key material)
             :kid (:kid material)
             :issuer "axxium"
             :audience "promethean+development"}))))

(defn- ^:async round-trip* []
  (let [material (await (signing-key/generate-ephemeral!))
        other (await (signing-key/generate-ephemeral!))
        opened (await (sign-and-open material))
        token (await (jose/sign-actor!
                      {:sub "actor_1"}
                      {:private-key (:private-key material)
                       :kid (:kid material)
                       :issuer "axxium"
                       :audience "promethean+development"
                       :expiry-hours 1}))
        hmac (await (-> (new jose/SignJWT #js {:sub "actor_1"})
                        (.setProtectedHeader #js {"alg" "HS256" "typ" "JWT"})
                        (.setIssuedAt)
                        (.setIssuer "axxium")
                        (.setAudience "promethean+development")
                        (.setExpirationTime "1h")
                        (.sign (.encode (new (.-TextEncoder js/globalThis)) "not-the-instance-key"))))
        jwks (discovery/jwks-document (:public-jwk material))
        did (discovery/did-document "https://axxium.promethean.rest" (:public-jwk material))]
    (is (= "actor_1" (:sub opened)))
    (is (= (:kid material) (get-in jwks [:keys 0 :kid])))
    (is (law/jwks-document? jwks))
    (is (law/did-document? did))
    (is (not (law/contains-private-member? jwks)))
    (is (not (law/contains-private-member? did)))
    (is (nil? (:d (:public-jwk material))))
    (is (= :rejected
           (try
             (await (jose/verify-actor!
                     token
                     {:public-key (:public-key material)
                      :kid (:kid other)
                      :issuer "axxium"
                      :audience "promethean+development"}))
             :accepted
             (catch :default _ :rejected)))
        "a header kid that is not this instance's kid is rejected")
    (is (= :rejected
           (try
             (await (jose/verify-actor!
                     token
                     {:public-key (:public-key other)
                      :kid (:kid material)
                      :issuer "axxium"
                      :audience "promethean+development"}))
             :accepted
             (catch :default _ :rejected)))
        "a token signed by another Ed25519 key is rejected")
    (is (= :rejected
           (try
             (await (jose/verify-actor!
                     hmac
                     {:public-key (:public-key material)
                      :kid (:kid material)
                      :issuer "axxium"
                      :audience "promethean+development"}))
             :accepted
             (catch :default _ :rejected)))
        "HS256 is not this instance's algorithm")))

(defn- ^:async failure
  [action]
  (try
    (await (action))
    :ok
    (catch :default error
      (is (= "Signing key is not loaded" (ex-message error)))
      (is (= #{:reason} (set (keys (ex-data error)))))
      (is (not (str/includes? (pr-str (ex-data error)) "PRIVATE")))
      (reason error))))

(defn- ^:async load-failures-carry-only-a-reason* []
  (let [dir (.mkdtempSync fs (path/join (.tmpdir os) "axxium-sign-"))
        file (path/join dir "signing-key.pem")]
    (try
      (is (= :missing-path
             (await (failure (fn []
                               (with-redefs [cfg/get-in-config (fn [_] nil)]
                                 (signing-key/load!)))))))
      (is (= :unreadable
             (await (failure (fn []
                               (with-redefs [cfg/get-in-config
                                             (fn [ks]
                                               (when (= ks [:signing/key-file])
                                                 (path/join dir "missing.pem")))]
                                 (signing-key/load!)))))))
      (.writeFileSync fs file "not a key" "utf8")
      (is (= :invalid-pem
             (await (failure (fn []
                               (with-redefs [cfg/get-in-config
                                             (fn [ks]
                                               (when (= ks [:signing/key-file]) file))]
                                 (signing-key/load!)))))))
      (.writeFileSync fs file "-----BEGIN PRIVATE KEY-----\nYQ==\n-----END PRIVATE KEY-----\n" "utf8")
      (is (= :invalid-key
             (await (failure (fn []
                               (with-redefs [cfg/get-in-config
                                             (fn [ks]
                                               (when (= ks [:signing/key-file]) file))]
                                 (signing-key/load!)))))))
      (finally
        (signing-key/unload!)
        (.rmSync fs dir #js {:recursive true :force true})))))

(defn- ^:async load-installs-the-thumbprint-or-the-override* []
  (let [material (await (signing-key/generate-ephemeral!))
        pem (await (jose-lib/exportPKCS8 (:private-key material)))
        dir (.mkdtempSync fs (path/join (.tmpdir os) "axxium-sign-"))
        file (path/join dir "signing-key.pem")]
    (try
      (.writeFileSync fs file pem #js {:mode 384 :flag "w"})
      (with-redefs [cfg/get-in-config (fn [ks]
                                        (case ks
                                          [:signing/key-file] file
                                          [:signing/kid] nil
                                          nil))]
        (let [loaded (await (signing-key/load!))]
          (is (= (:kid material) (:kid loaded)))
          (is (= #{:kid} (set (keys loaded))))
          (is (= (:kid material) (:kid (signing-key/public-jwk))))
          (is (nil? (:d (signing-key/public-jwk))))
          (is (= "actor_1" (:sub (await (sign-and-open (signing-key/current!))))))))
      (with-redefs [cfg/get-in-config (fn [ks]
                                        (case ks
                                          [:signing/key-file] file
                                          [:signing/kid] "custom-kid"
                                          nil))]
        (is (= "custom-kid" (:kid (await (signing-key/load!)))))
        (is (= "custom-kid" (:kid (signing-key/public-jwk)))))
      (with-redefs [cfg/get-in-config (fn [ks]
                                        (case ks
                                          [:signing/key-file] file
                                          [:signing/kid] "bad kid"
                                          nil))]
        (is (= :invalid-kid
               (await (failure signing-key/load!)))))
      (finally
        (signing-key/unload!)
        (.rmSync fs dir #js {:recursive true :force true})))))

(deftest eddsa-round-trip-rejects-a-foreign-kid-and-hs256
  (async done
    (-> (round-trip*)
        (.catch (fn [error]
                  (is false (str "eddsa round-trip rejected: " (safe-error error)))))
        (.finally done))))

(deftest load-failures-carry-only-a-reason
  (async done
    (-> (load-failures-carry-only-a-reason*)
        (.catch (fn [error]
                  (is false (str "load failures rejected: " (safe-error error)))))
        (.finally (fn []
                    (signing-key/unload!)
                    (done))))))

(deftest load-installs-the-thumbprint-or-the-override
  (async done
    (-> (load-installs-the-thumbprint-or-the-override*)
        (.catch (fn [error]
                  (is false (str "load install rejected: " (safe-error error)))))
        (.finally (fn []
                    (signing-key/unload!)
                    (done))))))
