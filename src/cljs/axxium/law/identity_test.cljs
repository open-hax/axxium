(ns axxium.law.identity-test
  (:require [axxium.law.actor :as actor]
            [axxium.law.atproto :as atproto]
            [cljs.test :refer [deftest is testing]]))

(deftest administrator-must-be-human
  (let [context {:auth/roles ["axxium/system-admin"]}]
    (is (true? (boolean (actor/system-admin? context {:kind "human"}))))
    (is (false? (boolean (actor/system-admin? context {:kind "agent"}))))))

(deftest identity-input-is-bounded-and-parsed
  (testing "valid AT Protocol identities"
    (is (atproto/did? "did:plc:ewvi7nxzyoun6zhxrhs64oiz"))
    (is (atproto/handle? "atproto.com"))
    (is (atproto/did? "did:web:example.com:users:alice")
        "path-based did:web identifiers use colon-delimited segments")
    (is (atproto/did? "did:web:localhost%3A8443")
        "did:web ports are percent-encoded"))
  (testing "unresolvable or unsafe identity shapes"
    (is (not (atproto/identity? "localhost")))
    (is (not (atproto/identity? "did:web:localhost/path")))
    (is (not (atproto/identity? (apply str (repeat 600 "a")))))))


(deftest credential-id-must-be-a-uuid-string
  (is (true? (actor/valid-credential-id? "e619cb36-f788-457d-ec3f-76d5fd47d1cc")))
  (is (true? (actor/valid-credential-id? "E619CB36-F788-457D-EC3F-76D5FD47D1CC")))
  (is (false? (actor/valid-credential-id? "not-a-uuid")))
  (is (false? (actor/valid-credential-id? "fixture-id")))
  (is (false? (actor/valid-credential-id? "")))
  (is (false? (actor/valid-credential-id? nil)))
  (is (false? (actor/valid-credential-id? 42)))
  (is (false? (actor/valid-credential-id?
               "e619cb36-f788-457d-ec3f-76d5fd47d1cc\n"))))

(deftest concurrent-binding-admission-classifies-winners
  (is (= :missing (atproto/concurrent-binding-admission "actor-a" nil)))
  (is (= :same-actor (atproto/concurrent-binding-admission "actor-a" {:id "actor-a"})))
  (is (= :other-actor (atproto/concurrent-binding-admission "actor-a" {:id "actor-b"}))))

