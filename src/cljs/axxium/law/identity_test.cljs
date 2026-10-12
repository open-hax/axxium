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
    (is (atproto/handle? "atproto.com")))
  (testing "unresolvable or unsafe identity shapes"
    (is (not (atproto/identity? "localhost")))
    (is (not (atproto/identity? "did:web:localhost/path")))
    (is (not (atproto/identity? (apply str (repeat 600 "a")))))))
