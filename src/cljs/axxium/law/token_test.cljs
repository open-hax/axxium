(ns axxium.law.token-test
  "Properties and examples for issuer admission, env/aud matching, and token validity."
  (:require [axxium.config :as cfg]
            [axxium.domain.token :as domain]
            [axxium.law.actor :as actor]
            [axxium.law.token :as law]
            [cljs.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private alphabet
  (vec "abcdefghijklmnopqrstuvwxyz0123456789"))

(def ^:private segment-tail
  (vec "abcdefghijklmnopqrstuvwxyz0123456789._-"))

(defn- gen-chars [alphabet-vec lo hi]
  (gen/fmap #(apply str %)
            (gen/vector (gen/elements alphabet-vec) lo hi)))

(def ^:private gen-segment
  (gen/let [head (gen/elements alphabet)
            tail (gen/vector (gen/elements segment-tail) 0 16)]
    (apply str head tail)))

(def ^:private gen-issuer
  (gen-chars alphabet 1 24))

(def ^:private gen-uuid
  (gen/let [a (gen-chars (vec "0123456789abcdef") 8 8)
            b (gen-chars (vec "0123456789abcdef") 4 4)
            c (gen-chars (vec "0123456789abcdef") 4 4)
            d (gen-chars (vec "0123456789abcdef") 4 4)
            e (gen-chars (vec "0123456789abcdef") 12 12)]
    (str a "-" b "-" c "-" d "-" e)))

(def ^:private gen-actor
  (gen/hash-map
   :id (gen/fmap #(str "actor_" %) (gen-chars alphabet 1 8))
   :entity_id (gen/fmap #(str "entity_" %) (gen-chars alphabet 1 8))
   :email (gen/return "a@b.test")
   :capabilities (gen/return [])
   :roles (gen/return [])
   :status (gen/return "active")))

(def ^:private gen-instance
  (gen/hash-map :iss gen-issuer :service gen-segment :env gen-segment))

(def ^:private gen-distinct-segments
  (gen/such-that (fn [[left right]] (not= left right))
                 (gen/tuple gen-segment gen-segment)
                 40))

(defspec audience-matches-its-own-env
  80
  (prop/for-all [service gen-segment
                 env gen-segment]
    (let [aud (domain/audience service env)]
      (and (= aud (str service "+" env))
           (law/token-env-matches-aud? aud env)))))

(defspec audience-injective-in-service-and-env
  80
  (prop/for-all [service gen-segment
                 env gen-segment
                 other-service gen-segment
                 other-env gen-segment]
    (= (= (domain/audience service env)
          (domain/audience other-service other-env))
       (and (= service other-service)
            (= env other-env)))))

(defspec a-different-env-breaks-aud-matching
  80
  (prop/for-all [service gen-segment
                 [env other] gen-distinct-segments]
    (not (law/token-env-matches-aud? (domain/audience service env) other))))

(defspec an-aud-that-is-not-service-plus-env-is-rejected
  80
  (prop/for-all [service gen-segment
                 env gen-segment]
    (and (not (law/token-env-matches-aud? service env))
         (not (law/token-env-matches-aud? (str "+" env) env))
         (not (law/token-env-matches-aud? (str service "+" env "+more") env))
         (not (law/token-env-matches-aud? (str service "+" env) (str env "x"))))))

(defspec issuer-admission-is-equality-on-issuers
  80
  (prop/for-all [iss gen-issuer
                 other gen-issuer]
    (and (law/iss-admitted? iss iss)
         (= (law/iss-admitted? iss other) (= iss other))
         (not (law/iss-admitted? "" iss))
         (not (law/iss-admitted? iss "has space")))))

(defspec issued-claims-preserve-cred-id-and-are-valid
  80
  (prop/for-all [actor gen-actor
                 cred-id gen-uuid
                 instance gen-instance]
    (let [claims (domain/actor-claims actor cred-id instance)]
      (and (actor/valid-credential-id? cred-id)
           (law/valid-token-claims? claims instance)
           (= cred-id (:cred_id claims))
           (= cred-id (:auth/cred-id (domain/auth-context actor cred-id)))
           (= (:iss instance) (:iss claims))
           (= (:env instance) (:env claims))
           (law/token-env-matches-aud? (:aud claims) (:env claims))))))

(defspec wrong-issuer-is-rejected
  80
  (prop/for-all [actor gen-actor
                 cred-id gen-uuid
                 instance gen-instance
                 other gen-issuer]
    (or (= other (:iss instance))
        (not (law/valid-token-claims? (assoc (domain/actor-claims actor cred-id instance)
                                             :iss other)
                                      instance)))))

(defspec env-that-does-not-match-aud-is-rejected
  80
  (prop/for-all [actor gen-actor
                 cred-id gen-uuid
                 instance gen-instance
                 other gen-segment]
    (or (= other (:env instance))
        (let [claims (domain/actor-claims actor cred-id instance)
              broken (assoc claims :env other)]
          (and (law/token-env-matches-aud? (:aud claims) (:env claims))
               (not (law/token-env-matches-aud? (:aud broken) other))
               (not (law/valid-token-claims? broken instance)))))))

(defspec malformed-cred-id-is-rejected
  40
  (prop/for-all [actor gen-actor
                 cred-id gen-uuid
                 instance gen-instance
                 bad (gen/one-of [(gen/return nil)
                                  (gen/return "")
                                  (gen/return "not-a-uuid")
                                  (gen-chars alphabet 1 12)])]
    (not (law/valid-token-claims? (assoc (domain/actor-claims actor cred-id instance)
                                         :cred_id bad)
                                  instance))))

(defspec configured-audience-must-be-service-plus-env
  40
  (prop/for-all [service gen-segment
                 env gen-segment]
    (let [aud (domain/audience service env)]
      (and (law/configured-audience-admitted? nil service env)
           (law/configured-audience-admitted? aud service env)
           (not (law/configured-audience-admitted? service service env))))))

(deftest promethean-development-audience-example
  (testing "aud is service+env"
    (is (= "promethean+development" (domain/audience "promethean" "development")))
    (is (true? (law/token-env-matches-aud? "promethean+development" "development"))))
  (testing "an env that is not the env inside aud is rejected"
    (is (false? (law/token-env-matches-aud? "promethean+development" "production")))
    (is (false? (law/token-env-matches-aud? "promethean" "development")))
    (is (false? (law/token-env-matches-aud? "promethean+development+extra" "extra")))
    (is (false? (law/token-env-matches-aud? "+development" "development"))))
  (testing "a plus inside a component is not a segment, so it cannot be an audience"
    (is (false? (law/segment? "pro+methean")))
    (is (false? (law/token-env-matches-aud? "pro+methean+development" "development")))))

(deftest wrong-issuer-and-missing-credential-examples
  (let [instance {:iss "axxium" :service "promethean" :env "development"}
        cred-id "e619cb36-f788-457d-ec3f-76d5fd47d1cc"
        actor {:id "actor_1" :entity_id "entity_1" :email "a@b.test"
               :capabilities [] :roles [] :status "active"}
        claims (domain/actor-claims actor cred-id instance)]
    (is (true? (law/admitted-instance? instance)))
    (is (true? (law/valid-token-claims? claims instance)))
    (is (= cred-id (:cred_id claims)))
    (is (= cred-id (:auth/cred-id (domain/auth-context actor cred-id))))
    (is (true? (law/iss-admitted? "axxium" "axxium")))
    (is (false? (law/iss-admitted? "other-instance" "axxium")))
    (is (false? (law/valid-token-claims? (assoc claims :iss "other-instance") instance)))
    (is (false? (law/valid-token-claims? (assoc claims :env "production") instance)))
    (is (false? (law/valid-token-claims? (dissoc claims :cred_id) instance)))
    (is (false? (law/valid-token-claims? (assoc claims :sub "") instance)))
    (is (false? (law/valid-token-claims? claims (assoc instance :iss "not this one"))))))

(deftest runtime-instance-satisfies-the-token-law
  (let [instance (cfg/instance)]
    (is (true? (law/admitted-instance? instance)))
    (is (= (domain/audience (:service instance) (:env instance))
           (cfg/get-in-config [:jwt/audience])))
    (is (= (:iss instance) (cfg/get-in-config [:jwt/issuer])))
    (is (true? (law/token-env-matches-aud? (cfg/get-in-config [:jwt/audience])
                                           (:env instance))))))
