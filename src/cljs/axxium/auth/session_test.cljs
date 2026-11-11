(ns axxium.auth.session-test
  "The acting credential id is sealed into the session and returned on the auth context."
  (:require [axxium.auth.session :as session]
            [axxium.auth.token :as token]
            [axxium.db :as db]
            [axxium.extern.http :as http]
            [axxium.law.actor :as actor]
            [axxium.shape.db :as shape]
            [cljs.test :refer [async deftest is]]
            [clojure.string :as str]
            [honey.sql :as sql]))

(def ^:private cred-id "e619cb36-f788-457d-ec3f-76d5fd47d1cc")

(defn- actor-row []
  {:id "actor_1"
   :entity_id "entity_1"
   :email "a@b.test"
   :capabilities [:axxium/login]
   :roles [:axxium/user]
   :credential_id cred-id})

(defn- ^:async agent-credential-reaches-the-auth-context* []
  (let [queries (atom [])]
    (with-redefs [http/authorization (fn [_] "Bearer axx_secret")
                  http/cookie (fn [_ _] nil)
                  db/query-one-sql (fn [honey]
                                     (swap! queries conj honey)
                                     (js/Promise.resolve (actor-row)))]
      (let [context (await (session/resolve-auth-context {}))]
        (is (= cred-id (:auth/cred-id context)))
        (is (= "actor_1" (:auth/actor-id context)))
        (is (= "entity_1" (:auth/entity-id context)))
        (let [[statement] (sql/format (first @queries))]
          (is (str/includes? statement "c.id"))
          (is (str/includes? statement "credential_id")))))))

(defn- ^:async session-cred-id-is-issued-and-looked-up* []
  (let [inserted (atom nil)
        looked-up (atom nil)
        bearer (atom nil)
        issued (atom nil)]
    (with-redefs [token/create-token (fn [_actor cred]
                                       (reset! issued cred)
                                       (js/Promise.resolve (str "signed:" cred)))
                  db/query-sql (fn [honey]
                                 (reset! inserted honey)
                                 (js/Promise.resolve []))
                  token/verify-token (fn [raw]
                                       (js/Promise.resolve {:sub "actor_1" :cred_id (subs raw 7)}))
                  db/query-one-sql (fn [honey]
                                     (reset! looked-up honey)
                                     (js/Promise.resolve (assoc (actor-row) :credential_id @issued)))
                  http/authorization (fn [_] @bearer)
                  http/cookie (fn [_ _] nil)]
      (let [{:keys [token cred-id]} (await (session/create-session! {:id "actor_1"}))
            _ (reset! bearer (str "Bearer " token))
            context (await (session/resolve-auth-context {}))
            [insert-sql & insert-params] (sql/format @inserted)
            [select-sql & select-params] (sql/format @looked-up)]
        (is (actor/valid-credential-id? cred-id))
        (is (= cred-id @issued))
        (is (= (str "signed:" cred-id) token))
        (is (str/includes? insert-sql "INSERT INTO sessions"))
        (is (str/includes? insert-sql "CAST"))
        (is (some #{cred-id} insert-params))
        (is (= cred-id (:auth/cred-id context)))
        (is (= "actor_1" (:auth/actor-id context)))
        (is (str/includes? select-sql "s.id"))
        (is (str/includes? select-sql "credential_id"))
        (is (some #{cred-id} select-params))))))

(deftest agent-credential-query-keeps-the-credential-id
  (let [[statement & params] (sql/format (shape/select-active-agent-credential "hash") {:numbered true})]
    (is (str/includes? statement "c.id"))
    (is (str/includes? statement "credential_id"))
    (is (some #{"hash"} params))))

(deftest session-lookup-requires-the-token-cred-id
  (let [[statement & params] (sql/format (shape/select-actor-by-session "actor_1" "hash" cred-id)
                                         {:numbered true})]
    (is (str/includes? statement "credential_id"))
    (is (str/includes? statement "CAST"))
    (is (some #{cred-id} params))
    (is (some #{"actor_1"} params))))

(defn- ^:async missing-credential-id-is-refused* []
  (with-redefs [http/authorization (fn [_] "Bearer axx_secret")
                http/cookie (fn [_ _] nil)
                db/query-one-sql (fn [_]
                                   (js/Promise.resolve (dissoc (actor-row) :credential_id)))]
    (is (nil? (await (session/resolve-auth-context {}))))))

(deftest missing-credential-id-is-refused
  (async done
    (-> (missing-credential-id-is-refused*)
        (.catch (fn [e] (is false (str "missing-credential-id-is-refused rejected: " (pr-str e)))))
        (.finally done))))

(deftest agent-credential-reaches-the-auth-context
  (async done
    (-> (agent-credential-reaches-the-auth-context*)
        (.catch (fn [e] (is false (str "agent-credential-reaches-the-auth-context rejected: " (pr-str e)))))
        (.finally done))))

(deftest session-cred-id-is-issued-and-looked-up
  (async done
    (-> (session-cred-id-is-issued-and-looked-up*)
        (.catch (fn [e] (is false (str "session-cred-id-is-issued-and-looked-up rejected: " (pr-str e)))))
        (.finally done))))

(defn- ^:async work-owner-proof* []
  (let [queries (atom [])
        actor (atom (actor-row))]
    (with-redefs [token/verify-token (fn [_] (js/Promise.resolve {:sub "actor_1" :cred_id cred-id}))
                  db/query-auth-proof! (fn [query]
                                     (swap! queries conj query)
                                     (js/Promise.resolve @actor))]
      (is (nil? (await (session/human-session-proof! "axx_agent_token"))))
      (is (empty? @queries))
      (let [proof (await (session/human-session-proof! "fixture-signed-browser-session"))]
        (is (= "actor_1" (:actor-id proof)))
        (is (= cred-id (:cred-id proof)))
        (is (= (db/q-select-active-human-session "actor_1" (:token-hash proof) cred-id)
               (first @queries))))
      (reset! actor nil)
      (is (nil? (await (session/human-session-proof! "fixture-signed-browser-session")))))))

(deftest work-owner-proof-requires-a-current-human-browser-session
  (async done
    (-> (work-owner-proof*)
        (.catch (fn [e] (is false (str "Work owner session proof rejected: " (pr-str e)))))
        (.finally done))))
