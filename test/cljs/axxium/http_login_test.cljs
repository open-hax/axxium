(ns axxium.http-login-test
  (:require [axxium.auth.session :as session]
            [axxium.db :as db]
            [axxium.extern.bcrypt :as bcrypt]
            [axxium.extern.http :as http]
            [axxium.routes.auth :as auth]
            [cljs.test :refer [deftest is]]))

(deftest ^:async actual-fastify-login
  (let [multibyte (apply str (repeat 36 "é"))
      ascii (apply str (repeat 73 "a"))
      over-utf8 (apply str (repeat 37 "é"))
      multi-hash (await (bcrypt/hash multibyte 4))
      legacy-hash (await (bcrypt/hash "12345678" 4))
      actor (atom {:id "synthetic" :email "legacy-user" :password_hash multi-hash})
      app (await (http/create! "http://localhost"))
      inject (fn [email password]
               (.inject app #js {:method "POST" :url "/api/auth/login"
                                 :payload #js {:email email :password password}}))]
  (with-redefs [db/q-select-actor-by-email-active identity
                db/query-one-sql (fn [email] (js/Promise.resolve (when (= email "legacy-user") @actor)))
                session/create-session! (fn [_] (js/Promise.resolve "synthetic-session"))]
    (http/route! app "POST" "/api/auth/login" auth/login!
                 {:session/cookie-name "session" :session/cookie-secure false :jwt/expiry-hours 1})
    (let [response (await (inject " LEGACY-USER " multibyte))]
      (is (= 200 (.-statusCode response)))
      (is (true? (.-ok (.json response))))
      (is (string? (aget (.-headers response) "set-cookie"))))
    (doseq [[password message] [[ascii "Invalid identity request"]
                               [over-utf8 "Password exceeds the supported byte length"]]]
      (let [response (await (inject "legacy-user" password))]
        (is (= 400 (.-statusCode response)))
        (is (= "invalid_request" (.-code (.json response))))
        (is (= message (.-error (.json response))))))
    (reset! actor (assoc @actor :password_hash legacy-hash))
    (is (= 200 (.-statusCode (await (inject "legacy-user" "12345678")))))
    (doseq [email ["legacy-user" "unknown"]]
      (let [response (await (inject email "wrong"))]
        (is (= 401 (.-statusCode response)))
        (is (= "invalid_credentials" (.-code (.json response))))
        (is (= "Invalid email or password" (.-error (.json response)))))))
  (await (.close app))))
