(ns axxium.identity-test
  (:require [axxium.domain.identity :as identity]
            [axxium.law.identity :as law]
            [axxium.shape.db :as queries]
            [cljs.test :refer [deftest is testing]]
            [honey.sql :as sql]))
(def sample-claims
  {:sub "actor_one" :entity_id "entity_one" :email "person@example.test"
   :display_name "Person" :jti "nonce" :iss "https://stealth.axxium.promethean.rest"
   :aud "https://yoga.axxium.promethean.rest" :iat 100 :exp 400 :purpose "identity-transfer"})
(deftest transfer-preserves-identity-without-copying-privileges
  (let [source {:id "actor_one" :entity_id "entity_one" :email "person@example.test"
                :display_name "Person" :password_hash "never-export" :roles ["admin"]
                :capabilities ["all"]}
        payload (identity/transfer-payload source "nonce")
        imported (identity/imported-actor (merge sample-claims {:roles ["admin"] :capabilities ["all"]}) "new-local-hash")]
    (is (= "actor_one" (:sub payload)))
    (is (= "entity_one" (:entity_id payload)))
    (is (not (contains? payload :password_hash)))
    (is (not (contains? payload :roles)))
    (is (not (contains? payload :capabilities)))
    (is (= ["axxium/user"] (:roles imported)))
    (is (= "new-local-hash" (:password-hash imported)))))
(deftest rejects-expired-future-and-invalid-purpose-transfers
  (is (= sample-claims (law/require-transfer! sample-claims 150)))
  (doseq [[changes now] [[{} 400] [{} 99] [{:exp 401} 150]
                         [{:purpose "admin-grant"} 150] [{:sub ""} 150]]]
    (is (thrown? cljs.core/ExceptionInfo (law/require-transfer! (merge sample-claims changes) now)))))
(deftest normalizes-email-but-never-password
  (is (= {:email "person@example.test" :password " secret with spaces "}
         (identity/normalize-credentials {:email " PERSON@example.test " :password " secret with spaces "}))))
(deftest query-parameters-remain-separate-and-structured
  (let [[statement & params] (sql/format (queries/insert-actor
                             {:id "actor_one" :entity-id "entity_one" :email "person@example.test"
                              :display-name "Person" :password-hash "hash"
                              :capabilities ["axxium/read"] :roles ["axxium/user"] :status "active"})
                           {:numbered true})]
    (is (string? statement))
    (is (= 8 (count params)))
    (is (some #{["axxium/user"]} params))
    (is (some #{["axxium/read"]} params))))
(deftest persisted-session-query-requires-active-unexpired-actor
  (let [[statement & params] (sql/format (queries/select-actor-by-session "actor_one" "token-hash") {:numbered true})]
    (is (= "SELECT a.* FROM actors AS a INNER JOIN sessions AS s ON a.id = s.actor_id WHERE (a.id = $1) AND (s.token_hash = $2) AND (s.expires_at > NOW()) AND (a.status = $3)" statement))
    (is (= ["actor_one" "token-hash" "active"] params))))
