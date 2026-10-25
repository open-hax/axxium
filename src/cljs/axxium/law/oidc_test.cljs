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

(deftest closed-unique-client-registration
  (is (= config (law/require-config! config)))
  (doseq [candidate [(assoc config :clients [])
                     (update config :clients #(conj % (first %)))
                     (update config :clients #(conj % (assoc (first %) :id "another-client")))
                     (assoc-in config [:clients 0 :secret] "short")
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
