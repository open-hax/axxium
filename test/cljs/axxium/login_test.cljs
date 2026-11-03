(ns axxium.login-test
  (:require [axxium.auth.session :as session]
            [axxium.db :as db]
            [axxium.extern.bcrypt :as bcrypt]
            [axxium.routes.auth :as auth]
            [cljs.test :refer [deftest is]]))

(deftest ^:async login-checks-bcrypt-byte-limit
  (let [password72 (apply str (repeat 72 "a"))
      hash72 (await (bcrypt/hash password72 4))
      multibyte72 (apply str (repeat 36 "é"))
      multibyte-hash (await (bcrypt/hash multibyte72 4))
      legacy-hash (await (bcrypt/hash "12345678" 4))
      calls (atom [])
      emails (atom [])
      actor (atom {:id "synthetic" :email "legacy-user" :password_hash hash72})
      compare bcrypt/compare]
  (with-redefs [db/q-select-actor-by-email-active (fn [email] (swap! emails conj email) email)
                db/query-one-sql (fn [_] (js/Promise.resolve @actor))
                session/create-session! (fn [_] (js/Promise.resolve "synthetic-session"))
                bcrypt/compare (fn [password hash]
                                 (swap! calls conj password)
                                 (compare password hash))]
    (is (true? (get-in (await (auth/login! {:body {:email "legacy-user" :password password72}})) [:body :ok])))
    (doseq [password [(str password72 "x") (apply str (repeat 37 "é"))]]
      (let [before (count @calls)
            error (try (await (auth/login! {:body {:email "legacy-user" :password password}}))
                       nil (catch :default e {:data (ex-data e) :message (ex-message e)}))]
        (is (= {:status 400 :code "invalid_request"} (:data error)))
        (is (= (if (= password (str password72 "x"))
                 "Invalid identity request"
                 "Password exceeds the supported byte length")
               (:message error)))
        (is (= before (count @calls)))))
    (reset! actor (assoc @actor :password_hash multibyte-hash))
    (is (true? (get-in (await (auth/login! {:body {:email " LEGACY-USER " :password multibyte72}})) [:body :ok])))
    (is (= "legacy-user" (last @emails)))
    (reset! actor (assoc @actor :password_hash legacy-hash))
    (is (true? (get-in (await (auth/login! {:body {:email "legacy-user" :password "12345678"}})) [:body :ok])))
    (let [wrong (try (await (auth/login! {:body {:email "legacy-user" :password "wrong"}}))
                     nil (catch :default e (ex-data e)))]
      (reset! actor nil)
      (is (= wrong (try (await (auth/login! {:body {:email "legacy-user" :password "wrong"}}))
                        nil (catch :default e (ex-data e)))))
      (is (= {:status 401 :code "invalid_credentials"} wrong))))))
