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

(deftest auth-state-capacity-admission-decides-new-keys
  (is (= :admit (atproto/auth-state-capacity-admission 3 3 true)))
  (is (= :admit (atproto/auth-state-capacity-admission 2 3 false)))
  (is (= :refuse (atproto/auth-state-capacity-admission 3 3 false))))

(deftest oauth-saved-state-contract
  (is (true? (atproto/valid-oauth-state-store-key? "state-abc")))
  (is (false? (atproto/valid-oauth-state-store-key? "")))
  (is (false? (atproto/valid-oauth-state-store-key? nil)))
  (is (true? (atproto/valid-oauth-saved-state?
              {:iss "https://bsky.social"
               :verifier "abc"
               :auth-method {:method "none"}
               :dpop-jwk {:kty "EC" :crv "P-256" :x "xcoord" :y "ycoord" :d "dpriv"}})))
  (is (true? (atproto/valid-oauth-saved-state?
              {:iss "https://bsky.social"
               :verifier "abc"
               :auth-method {:method "private_key_jwt" :kid "k1"}
               :dpop-jwk {:kty "EC" :crv "P-256" :x "xcoord" :y "ycoord" :d "dpriv"}
               :app-state "link|actor|tok"})))
  (is (true? (atproto/valid-oauth-saved-state?
              {:iss "https://bsky.social"
               :verifier "abc"
               :auth-method {:method "none"}
               :dpop-jwk {:kty "OKP" :crv "Ed25519" :x "xcoord" :d "dpriv"}})))
  (is (true? (atproto/valid-oauth-saved-state?
              {:iss "https://bsky.social"
               :verifier "abc"
               :auth-method {:method "none"}
               :dpop-jwk {:kty "RSA" :n "modulus" :e "AQAB" :d "dpriv"
                          :p "p1" :q "q1" :dp "dp1" :dq "dq1" :qi "qi1"}})))
  ;; Codex PRRT_kwDOU4Vg086s2hg3: Jose/Node RSA private import also needs the
  ;; CRT members, so n/e/d alone must be refused.
  (is (false? (atproto/valid-oauth-saved-state?
               {:iss "https://bsky.social"
                :verifier "abc"
                :auth-method {:method "none"}
                :dpop-jwk {:kty "RSA" :n "modulus" :e "AQAB" :d "dpriv"}})))
  (doseq [k [:p :q :dp :dq :qi]]
    (is (false? (atproto/valid-oauth-saved-state?
                 {:iss "https://bsky.social"
                  :verifier "abc"
                  :auth-method {:method "none"}
                  :dpop-jwk (dissoc {:kty "RSA" :n "modulus" :e "AQAB" :d "dpriv"
                                     :p "p1" :q "q1" :dp "dp1" :dq "dq1" :qi "qi1"} k)}))
        (str "RSA private JWK without " (name k) " is refused")))
  ;; kty alone is insufficient — private members required for SDK import
  (is (false? (atproto/valid-oauth-saved-state?
               {:iss "https://bsky.social"
                :verifier "abc"
                :auth-method {:method "none"}
                :dpop-jwk {:kty "EC"}})))
  (is (false? (atproto/valid-oauth-saved-state?
               {:iss "https://bsky.social"
                :verifier "abc"
                :auth-method {:method "none"}
                :dpop-jwk {:kty "EC" :crv "P-256" :x "x" :y "y"}})))
  (is (false? (atproto/valid-oauth-saved-state? {})))
  (is (false? (atproto/valid-oauth-saved-state?
               {:iss "https://bsky.social" :verifier "abc"
                :auth-method {:method "none"}}))))


(deftest actor-page-cursor-admission-lives-in-law
  ;; Codex PRRT_kwDOU4Vg086ujcPy (riatzukiza/axxium#10): the untrusted `after`
  ;; cursor contract belongs to axxium.law.actor, not the route.
  (is (true? (actor/valid-actor-page-cursor? "actor_1")))
  (is (true? (actor/valid-actor-page-cursor? (apply str (repeat 256 "a")))))
  (is (false? (actor/valid-actor-page-cursor? "")))
  (is (false? (actor/valid-actor-page-cursor? nil)))
  (is (false? (actor/valid-actor-page-cursor? 42)))
  (is (false? (actor/valid-actor-page-cursor? (apply str (repeat 257 "a"))))))
