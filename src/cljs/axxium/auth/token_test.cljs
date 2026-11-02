(ns axxium.auth.token-test
  "Issued EdDSA tokens carry cred_id, iss, aud, and env, and verification applies the token law."
  (:require [axxium.auth.token :as token]
            [axxium.config :as cfg]
            [axxium.domain.token :as domain]
            [axxium.extern.jose :as jose]
            [axxium.extern.signing-key :as signing-key]
            [axxium.law.actor :as actor]
            [cljs.test :refer [async deftest is]]))

(def ^:private cred-id "e619cb36-f788-457d-ec3f-76d5fd47d1cc")

(defn- actor []
  {:id "actor_1" :entity_id "entity_1" :email "a@b.test"
   :capabilities [:axxium/login] :roles [:axxium/user] :status "active"})

(defn- signing-options [material instance audience]
  {:private-key (:private-key material)
   :public-key (:public-key material)
   :kid (:kid material)
   :issuer (:iss instance)
   :audience audience
   :expiry-hours 1})

(defn- ^:async issued-token-carries-the-acting-credential* []
  (let [material (await (signing-key/generate-ephemeral!))]
    (signing-key/install! material)
    (try
      (let [instance (cfg/instance)
            aud (domain/audience (:service instance) (:env instance))
            sealed (await (token/create-token (actor) cred-id))
            claims (await (token/verify-token sealed))]
        (is (actor/valid-credential-id? (:cred_id claims)))
        (is (= cred-id (:cred_id claims)))
        (is (= "actor_1" (:sub claims)))
        (is (= (:iss instance) (:iss claims)))
        (is (= (:env instance) (:env claims)))
        (is (= aud (:aud claims))))
      (finally
        (signing-key/unload!)))))

(defn- ^:async wrong-issuer-is-rejected* []
  (let [material (await (signing-key/generate-ephemeral!))]
    (signing-key/install! material)
    (try
      (let [instance (cfg/instance)
            aud (domain/audience (:service instance) (:env instance))
            sealed (await (jose/sign-actor!
                           {:sub "actor_1" :cred_id cred-id :env (:env instance)}
                           (assoc (signing-options material instance aud) :issuer "other-instance")))]
        (is (= :rejected
               (try
                 (await (token/verify-token sealed))
                 :accepted
                 (catch :default _ :rejected)))
            "a token with the wrong iss is rejected"))
      (finally
        (signing-key/unload!)))))

(defn- ^:async env-that-does-not-match-aud-is-rejected* []
  (let [material (await (signing-key/generate-ephemeral!))]
    (signing-key/install! material)
    (try
      (let [instance (cfg/instance)
            aud (domain/audience (:service instance) (:env instance))
            other-env (if (= "production" (:env instance)) "development" "production")
            sealed (await (jose/sign-actor!
                           {:sub "actor_1" :cred_id cred-id :env other-env}
                           (signing-options material instance aud)))]
        (is (= :rejected
               (try
                 (await (token/verify-token sealed))
                 :accepted
                 (catch :default _ :rejected)))
            "a token whose env does not match aud is rejected"))
      (finally
        (signing-key/unload!)))))

(defn- ^:async claims-outside-the-law-are-not-issued* []
  (let [material (await (signing-key/generate-ephemeral!))]
    (signing-key/install! material)
    (try
      (is (= :refused
             (try
               (await (token/create-token (assoc (actor) :id "") cred-id))
               :issued
               (catch :default _ :refused)))
          "a blank subject is outside the token law")
      (is (= :refused
             (try
               (await (token/create-token (actor) "not-a-uuid"))
               :issued
               (catch :default _ :refused)))
          "a cred_id that is not a credential id is outside the token law")
      (finally
        (signing-key/unload!)))))

(deftest issued-token-carries-the-acting-credential
  (async done
    (-> (issued-token-carries-the-acting-credential*)
        (.catch (fn [e] (is false (str "issued-token-carries-the-acting-credential rejected: " (pr-str e)))))
        (.finally done))))

(deftest wrong-issuer-is-rejected
  (async done
    (-> (wrong-issuer-is-rejected*)
        (.catch (fn [e] (is false (str "wrong-issuer-is-rejected rejected: " (pr-str e)))))
        (.finally done))))

(deftest env-that-does-not-match-aud-is-rejected
  (async done
    (-> (env-that-does-not-match-aud-is-rejected*)
        (.catch (fn [e] (is false (str "env-that-does-not-match-aud-is-rejected rejected: " (pr-str e)))))
        (.finally done))))

(deftest claims-outside-the-law-are-not-issued
  (async done
    (-> (claims-outside-the-law-are-not-issued*)
        (.catch (fn [e] (is false (str "claims-outside-the-law-are-not-issued rejected: " (pr-str e)))))
        (.finally done))))
