(ns axxium.law.oidc-test
  "Authority config refuses ambiguous client registration and weak secrets."
  (:require [axxium.law.oidc :as law]
            [axxium.extern.oidc-provider :as provider]
            [cljs.test :refer [deftest is]]))

(def config
  "Synthetic configuration without real key material."
  {:issuer "https://identity.example.test"
   :clients [{:id "knoxx-stealth" :secret (apply str (repeat 32 "x"))
              :redirect-uris ["https://knoxx.example.test/callback"]}]
   :cookie-keys [(apply str (repeat 32 "k"))] :jwks {}})

(defn- admitted-config
  "Observe a declared contract refusal; unrelated runtime errors still escape."
  [candidate]
  (try (law/require-config! candidate)
       (catch cljs.core/ExceptionInfo _ nil)))

(deftest closed-unique-client-registration
  (is (= config (admitted-config config)))
  (doseq [candidate [(assoc config :clients [])
                     (update config :clients #(conj % (first %)))
                     (update config :clients #(conj % (assoc (first %) :id "another-client")))
                     (assoc-in config [:clients 0 :secret] "short")
                     (assoc-in config [:clients 0 :secret] (apply str (repeat 31 "x")))
                     (assoc-in config [:clients 0 :id] "")
                     (assoc config :issuer "")
                     (assoc-in config [:clients 0 :unexpected-privilege] true)
                     (assoc-in config [:clients 0 :redirect-uris] [])
                     (assoc config :dynamic-registration true)]]
    (is (thrown? cljs.core/ExceptionInfo (law/require-config! candidate)))))

(deftest unqualified-urls-and-absent-private-keys-refuse
  (doseq [candidate [(assoc config :issuer "http://identity.example.test")
                     (assoc config :issuer "https://user:pass@identity.example.test")
                     (assoc config :issuer "https://identity.example.test#fragment")
                     (assoc-in config [:clients 0 :redirect-uris] ["http://knoxx.example.test/callback"])
                     config]]
    (is (thrown? cljs.core/ExceptionInfo (provider/create-provider! nil candidate identity identity)))))

(deftest interaction-consent-is-principal-and-scope-bound
  (let [context {:expected-uid "interaction" :actual-uid "interaction" :actor-id "a"
                 :session-actor-id "a" :prompt "consent" :decision "approve"
                 :scopes ["openid" "profile"] :claims ["name"]}]
    (is (= {:decision :consent} (law/interaction-admission context)))
    (doseq [[changes status] [[{:actual-uid "other"} 400]
                              [{:actor-id nil} 401]
                              [{:scopes ["openid" "admin"]} 403]
                              [{:scopes ["profile"]} 403]
                              [{:claims ["password_hash"]} 403]
                              [{:decision "yes"} 400]]]
      (is (= {:decision :refuse :status status}
             (law/interaction-admission (merge context changes)))))
    (is (= {:decision :restart-login} (law/interaction-admission (assoc context :session-actor-id "b"))))
    (is (= {:decision :login} (law/interaction-admission (assoc context :prompt "login"))))
    (is (= {:decision :deny} (law/interaction-admission (assoc context :decision "deny"))))))

(deftest root-bound-token-lifetimes
  (is (= 300 (law/bounded-token-lifetime 1000000 100000 300)))
  (is (= 899 (law/bounded-token-lifetime 1000000 100001 86400)))
  (doseq [bound [nil 100000 99999 100999]]
    (is (nil? (law/bounded-token-lifetime bound 100000 300)))))

(deftest device-login-return-is-exact-and-bounded
  (doseq [path ["/oidc/device" "/oidc/device?user_code=ABCD-EFGH"]]
    (is (= path (law/device-login-return path))))
  (doseq [path [nil {} "https://evil.test" "/oidc/device/auth" "/oidc/device/confirm"
                "/oidc/device?user_code=ABCD&user_code=EFGH" "/oidc/device?user_code=%2f%2fevil.test"
                "/oidc/device?user_code=ABCD#fragment" "/oidc/device?user_code=ABCD\n"
                (str "/oidc/device?user_code=" (apply str (repeat 65 "a")))]]
    (is (= "/oidc/device" (law/device-login-return path)))))

(deftest registered-client-redirect-count-boundary
  (let [uris (mapv #(str "https://knoxx.example.test/callback/" %) (range 16))
        at-limit (assoc-in config [:clients 0 :redirect-uris] uris)
        over-limit (assoc-in config [:clients 0 :redirect-uris] (conj uris "https://knoxx.example.test/extra"))]
    (is (= at-limit (try (law/require-config! at-limit)
                         (catch cljs.core/ExceptionInfo _ nil))))
    (is (thrown? cljs.core/ExceptionInfo (law/require-config! over-limit)))))

(defn- uri-of-length
  "A syntactically valid HTTPS callback whose ASCII path reaches the boundary."
  [length]
  (let [prefix "https://identity.example.test/"]
    (str prefix (apply str (repeat (- length (count prefix)) "p")))))

(deftest registered-issuer-and-redirect-uri-lengths
  (doseq [length [2047 2048]]
    (let [uri (uri-of-length length)]
      (is (= length (count uri)))
      (doseq [candidate [(assoc config :issuer uri)
                         (assoc-in config [:clients 0 :redirect-uris] [uri])]]
        (is (= candidate (admitted-config candidate))))))
  (doseq [uri ["" (uri-of-length 2049)]]
    (doseq [candidate [(assoc config :issuer uri)
                       (assoc-in config [:clients 0 :redirect-uris] [uri])]]
      (is (thrown? cljs.core/ExceptionInfo (law/require-config! candidate))))))

(deftest registered-client-identifiers-and-secret-lengths
  (doseq [id ["c" (apply str (repeat 128 "c"))]]
    (let [candidate (assoc-in config [:clients 0 :id] id)]
      (is (= candidate (admitted-config candidate)))))
  (doseq [id ["" (apply str (repeat 129 "c"))]]
    (is (thrown? cljs.core/ExceptionInfo
          (law/require-config! (assoc-in config [:clients 0 :id] id)))))
  (doseq [length [32 512]]
    (let [secret (apply str (repeat length "s"))]
      (doseq [candidate [(assoc-in config [:clients 0 :secret] secret)
                         (assoc config :cookie-keys [secret])]]
        (is (= candidate (admitted-config candidate))))))
  (doseq [length [31 513]]
    (let [secret (apply str (repeat length "s"))]
      (doseq [candidate [(assoc-in config [:clients 0 :secret] secret)
                         (assoc config :cookie-keys [secret])]]
        (is (thrown? cljs.core/ExceptionInfo (law/require-config! candidate)))))))

(deftest registered-client-and-cookie-key-collection-limits
  (let [client (first (:clients config))
        clients (mapv #(assoc client :id (str "client-" %)
                             :secret (str (apply str (repeat 32 "s")) %)) (range 64))
        at-limit (assoc config :clients clients)
        extra (assoc client :id "extra-client" :secret (str (apply str (repeat 32 "s")) "extra"))]
    (is (= at-limit (admitted-config at-limit)))
    (is (thrown? cljs.core/ExceptionInfo
          (law/require-config! (assoc config :clients (conj clients extra))))))
  (let [keys (mapv #(str (apply str (repeat 32 "k")) %) (range 3))
        at-limit (assoc config :cookie-keys keys)]
    (is (= at-limit (admitted-config at-limit)))
    (doseq [candidate [(assoc config :cookie-keys [])
                       (assoc config :cookie-keys (conj keys (apply str (repeat 32 "extra"))))]]
      (is (thrown? cljs.core/ExceptionInfo (law/require-config! candidate))))))
