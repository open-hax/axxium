(ns axxium.extern.identity-operator-binding-test
  "Exercise portal callers against a synthetic identity seed."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.db :as db]
            [axxium.domain.github-identity :as gi]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.state :as state]
            [axxium.extern.github-app :as gh]
            [axxium.extern.github-identity-mcp :as mcp]
            [axxium.extern.http :as http]
            [axxium.extern.jose :as jose]
            [axxium.extern.ledger-file :as disk]
            [axxium.extern.signing-key :as signing-key]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.github-identity :as law]
            [axxium.routes.github-identity :as routes]
            [axxium.shape.db :as db-shape]
            [clio.shape.edn :as edn]
            [cljs.test :refer [async deftest is]]
            [honey.sql :as sql]))

(def ^:private host "axxium.example")
(def ^:private operator "actor_11111111-1111-4111-8111-111111111111")
(def ^:private unrelated "actor_22222222-2222-4222-8222-222222222222")
(def ^:private root-did (str "did:web:" host ":actors:" unrelated))
(def ^:private child-did (str root-did ":child"))
(def ^:private session-id "11111111-1111-4111-8111-222222222222")

(defn- seeded-ledger []
  (let [policy {:policy/repos #{"example/repo"} :policy/permissions {"contents" "read"}
                :policy/actions #{:read} :policy/forbid #{}}
        seed {:root {:id unrelated :name "Seed root" :policy policy}
              :nodes [{:id (str unrelated ":child") :parent unrelated :name "Seed child"
                       :kind :agent :policy policy}]}
        next-id (atom 9000)
        ids (fn [] {:decision-id (fx/event-uuid (swap! next-id inc))
                    :effect-id (fx/event-uuid (swap! next-id inc)) :at fx/t1})]
    (:ledger (gi/seed-ledger (gi/resolve-seed seed host) ids))))

(defn- ^:async operator-binding* []
  (let [handlers (atom {})
        durable (atom (seeded-ledger))
        binding (atom {:actor-id operator :root-did root-did})
        human-live? (atom true)
        conversions (atom 0)
        invoke! (fn [path actor token body]
                  ((get @handlers path)
                   (cond-> {:token token :body body}
                     actor (assoc :context {:auth/actor-id actor :auth/cred-id session-id}))
                   nil))
        call! (partial invoke! "/api/identity/admin")
        callback! (fn [state-actor]
                    (let [state (str state-actor ".synthetic")]
                      ((get @handlers law/manifest-callback-path)
                       {:context {:auth/actor-id operator :auth/cred-id session-id}
                        :token "verified-session" :query {"state" state "code" "synthetic"}
                        :cookie {:state state :nonce child-did :verifier root-did}} nil)))]
    (with-redefs [cfg/get-in-config (fn [[key]]
                                    (case key
                                      :axxium/public-base-url (str "https://" host)
                                      :github-identity/ledger-file "identity-operator-binding"
                                      :identity/root-operator-binding @binding
                                      nil))
                  db/query-auth-proof! (fn [_]
                                         (case @human-live?
                                           :unavailable (throw (ex-info "Synthetic database outage" {}))
                                           true {:id operator}
                                           nil))
                  disk/load-ledger (fn [_] @durable)
                  disk/append-batch! (fn [_ _ after] (reset! durable after))
                  gh/convert-manifest (fn [_ _]
                                        (swap! conversions inc)
                                        (throw (ex-info "Stop at the synthetic provider boundary" {})))
                  http/clear-state-cookie! (fn ([_ _] nil) ([_ _ _] nil))
                  http/cookie (fn [req _] (:cookie req))
                  http/post! (fn [_ path handler] (swap! handlers assoc path handler))
                  http/get! (fn [_ path handler] (swap! handlers assoc path handler))
                  http/body (fn [req] (:body req))
                  http/query-param (fn [req key] (get (:query req) key))
                  http/send! (fn [_ status body] {:status status :body body})
                  http/send-secret! (fn [_ status body] {:status status :body body})
                  jose/verify-google-state! (fn [opened _] opened)
                  session/extract-auth-token (fn [req] (:token req))
                  session/resolve-auth-context (fn [req] (:context req))
                  signing-key/current! (fn [] {})
                  tc/read-key-file (constantly :configured)]
      (routes/register-github-identity-routes! nil)
      (is (= 401 (:status (await (call! nil nil {:op "list"})))))
      (let [listed (await (call! operator "verified-session" {:op "list"}))]
        (is (= 200 (:status listed)))
        (is (= [root-did child-did] (mapv :did (get-in listed [:body :actors])))))
      (is (= 400 (:status (await (callback! unrelated)))))
      (is (zero? @conversions) "another account's signed manifest state cannot reach the provider")
      (is (= 502 (:status (await (callback! operator)))))
      (is (= 1 @conversions) "the exact actor and mapped parent reach the synthetic provider")
      (let [suspend {:op "suspend" :did child-did}]
        (is (= 403 (:status (await (call! unrelated "other-session" suspend))))
            "another account's canonical DID cannot claim the configured root")
        (is (= 403 (:status (await (call! operator "axx_agent" suspend))))
            "an agent credential cannot claim the configured root")
        (reset! human-live? false)
        (is (= 403 (:status (await (call! operator "revoked-session" suspend))))
            "a session revoked before the identity operation cannot bind")
        (reset! human-live? true)
        (swap! binding assoc :root-did "did:web:axxium.example:actors:missing")
        (is (= 403 (:status (await (call! operator "verified-session" suspend)))))
        (swap! binding assoc :root-did child-did)
        (is (= 403 (:status (await (call! operator "verified-session" suspend)))))
        (swap! binding assoc :root-did root-did)
        (is (= 200 (:status (await (call! operator "verified-session" suspend)))))
        (is (= :suspended (gi/status-of (state/fold @durable) child-did))))
      (doseq [[session-state status error] [[false 403 :unauthenticated]
                                            [:unavailable 503 :session-unavailable]]]
        (reset! human-live? true)
        (let [before @durable]
          (with-redefs [mcp/call
                        (fn [{:keys [ledger ids]} context _ _]
                          (is (= {:actor root-did} context))
                          (reset! human-live? session-state)
                          (let [decision (gi/decide-mint ledger (state/fold ledger)
                                                         root-did child-did "synthetic" {} (ids))]
                            {:result {:token "synthetic-token"} :ledger (:ledger decision)}))]
            (let [out (await (invoke! "/api/github/mcp" operator "verified-session"
                                      {:tool "token_for" :arguments "{}"}))]
              (is (= status (:status out)))
              (is (= {:error error} (edn/read-one (get-in out [:body :edn]))))
              (is (not= before @durable) "the decision ledger survives discarded token delivery")))))
      (reset! binding nil)
      (is (= 200 (:status (await (call! unrelated "other-session" {:op "list"}))))
          "without a binding, the existing canonical DID behavior remains"))))

(deftest binding-configuration-refuses-partial-and-invalid-values
  (is (nil? (cfg/root-operator-binding nil nil)))
  (is (= {:actor-id operator :root-did root-did}
         (cfg/root-operator-binding operator root-did)))
  (doseq [[account target] [[operator nil] [nil root-did] ["" ""] ["operator" root-did]
                           [operator "alice"]]]
    (is (thrown? js/Error (cfg/root-operator-binding account target)))))

(deftest a-suspended-root-is-not-an-operator-target
  (let [st (state/fold (seeded-ledger))]
    (is (gi/active-human-root? st root-did))
    (is (not (gi/active-human-root?
              (assoc-in st [:resources (law/actor-resource-id root-did)
                            :resource/metadata :actor/status] :suspended)
              root-did)))))

(deftest human-session-query-retains-the-exact-session-predicates
  (let [[statement & params]
        (sql/format (db-shape/select-active-human-session operator "synthetic-hash" session-id)
                    {:numbered true})]
    (is (re-find #"JOIN sessions AS s .* JOIN entities AS e" statement))
    (is (re-find #"a\.id = .*s\.token_hash = .*s\.id = CAST.*a\.status = .*s\.expires_at > NOW\(\).*e\.kind =" statement))
    (is (= [operator "synthetic-hash" session-id "active" "human"] (vec params)))))

(deftest portal-binds-only-the-configured-live-human-operator
  (async done
    (-> (operator-binding*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))
