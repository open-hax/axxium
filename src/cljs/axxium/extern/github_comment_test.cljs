(ns axxium.extern.github-comment-test
  (:require [axxium.domain.github-identity :as identity]
            [axxium.domain.kernel-fixture :as fixture]
            [axxium.domain.state :as state]
            [axxium.extern.github-app :as app]
            [axxium.extern.github-identity-mcp :as mcp]
            [axxium.extern.ledger-queue :as queue]
            [cljs.test :refer [async deftest is]]))

(def root "did:web:example.invalid:actors:owner")
(def actor (str root ":codex"))
(def leased-actor (str actor ":session"))
(def outsider (str root ":other"))
(def repo "example/widgets")
(def request {:actor/did actor :credential/label "github-app" :github/repo repo :github/number 42
              :github/body "Synthetic comment" :github/request-id "00000000-0000-4000-8000-000000000007"})
(def policy {:policy/repos #{repo}
             :policy/permissions {"issues" "write"}
             :policy/actions #{:read :comment}
             :policy/forbid #{:force-push}})

(defn- fixture-state [lease-until]
  (let [counter (atom 100)
        clock (atom fixture/t1)
        ids (fn [] {:decision-id (fixture/event-uuid (swap! counter inc))
                    :effect-id (fixture/event-uuid (swap! counter inc)) :at @clock})
        seed {:root {:id root :name "Root" :policy policy}
              :nodes [{:id actor :parent root :name "Codex" :policy policy}
                      {:id outsider :parent root :name "Other" :policy policy}]}
        seeded (:ledger (identity/seed-ledger (identity/resolve-seed seed "example.invalid") ids))
        seeded (if lease-until
                 (:ledger (identity/open-session seeded (state/fold seeded) actor actor leased-actor
                                                "Session" policy lease-until @clock (ids)))
                 seeded)
        credential-holder (if lease-until leased-actor actor)
        lg (:ledger (identity/provision-credential
                     seeded (state/fold seeded) root credential-holder "github-app" :github-app
                     {:credential/key-file "/synthetic"}
                     {:github/app-id 56 :github/app-slug "codex-test" :github/installation-id 78}
                     (ids)))
        durable (atom lg)
        fail-size (atom nil)
        storage {:load! (fn [_] @durable)
                 :append! (fn [_ before after]
                            (is (= before @durable))
                            (when (= (count after) @fail-size)
                              (throw (js/Error. "synthetic append failure")))
                            (reset! durable after))}]
    {:durable durable :storage storage :fail-size fail-size :ids ids :clock clock}))

(defn- invoke! [writer {:keys [ids]} initiator args fetch reauthorize!]
  (queue/submit-checkpointed!
   writer "synthetic" (fn [lg checkpoint!]
                         (mcp/call {:ledger lg :checkpoint! checkpoint! :reauthorize! reauthorize!
                                    :fetch fetch :ids ids :cache (atom {}) :key-dir "/synthetic"}
                                   {:actor initiator} "github_comment" args))))

(defn- response [status value]
  (js/Promise.resolve #js {:status status :ok (< status 400)
                           :json (fn [] (js/Promise.resolve (clj->js value)))}))

(defn- provider [durable calls outcome]
  (fn [url options]
    (let [method (.-method options)
          auth (.-authorization (.-headers options))
          payload (some-> (.-body options) js/JSON.parse (js->clj :keywordize-keys true))]
      (swap! calls conj [method url])
      (cond
        (= ["GET" "https://api.github.com/app/installations/78"] [method url])
        (response 200 {:id 78 :app_id 56 :app_slug (if (= :wrong-app outcome) "different-app" "codex-test")
                       :target_id 12 :target_type "User"
                       :account {:id 12 :login "example"} :permissions {:issues "write"}})

        (= ["POST" "https://api.github.com/app/installations/78/access_tokens"] [method url])
        (let [metadata? (= [:metadata] (vec (keys (:permissions payload))))]
          (is (= (if metadata? {:repositories ["widgets"] :permissions {:metadata "read"}}
                               {:repository_ids [90] :permissions {:metadata "read" :issues "write"}})
                 payload))
          (response 201 {:token (if metadata? "fixture-meta" "fixture-write")
                         :expires_at "2099-01-01T00:00:00Z" :permissions (:permissions payload)
                         :repositories [{:id 90 :full_name repo :owner {:id 12 :login "example"}}]}))

        (= ["POST" "https://api.github.com/repos/example/widgets/issues/42/comments"] [method url])
        (do
          (is (= ["Bearer fixture-write" {:body "Synthetic comment"}] [auth payload]))
          (is (= :axxium.github/comment-intended (:event/type (last @durable))))
          (is (= :axxium.policy/decided (:event/type (nth @durable (- (count @durable) 2)))))
          (is (not (re-find #"Synthetic comment|fixture-write" (pr-str @durable))))
          (if (= :timeout outcome)
            (js/Promise.reject (js/Error. "synthetic timeout"))
            (response 201 {:id 51 :body (:body payload)
                           :html_url "https://github.com/example/widgets/pull/42#issuecomment-51"
                           :issue_url "https://api.github.com/repos/example/widgets/issues/42"
                           :user {:login (if (= :foreign outcome) "foreign[bot]" "codex-test[bot]") :type "Bot" :id 99}
                           :performed_via_github_app {:id 56 :slug "codex-test"}})))

        (= ["DELETE" "https://api.github.com/installation/token"] [method url])
        (do (is (contains? #{"Bearer fixture-meta" "Bearer fixture-write"} auth)) (response 204 {}))

        :else (js/Promise.reject (js/Error. "unexpected provider endpoint"))))))

(defn- ^:async verify-managed-comment* []
  (with-redefs [app/read-key-file (fn [_ _] "fixture-pem")
                app/app-jwt (fn [& _] "fixture-jwt")]
    (let [{:keys [durable storage] :as fixture} (fixture-state nil)
          writer (queue/create storage)
          calls (atom [])
          fetch (provider durable calls :ok)]
      (doseq [[caller args] [[outsider request] [actor (assoc request :github/repo "example/elsewhere")]]]
        (is (= :forbidden (:error (await (invoke! writer fixture caller args fetch (constantly true)))))))
      (is (empty? @calls))
      (let [other-calls (atom [])]
        (is (= :installation-scope-mismatch
               (:error (await (invoke! writer fixture actor request (provider durable other-calls :wrong-app)
                                       (constantly true))))))
        (is (= 1 (count @other-calls))))
      (let [out (await (invoke! writer fixture actor request fetch (constantly true)))
            restarted (queue/create storage)]
        (is (= "codex-test[bot]" (get-in out [:result :github/actor])))
        (is (= 51 (get-in out [:result :github/comment-id])))
        (is (= 99 (get-in out [:result :github/actor-id])))
        (is (= :axxium.github/comment-posted (:event/type (last @durable))))
        (is (= 6 (count @calls)))
        (is (= out (await (invoke! restarted fixture root request fetch (constantly true)))))
        (is (= :comment-request-conflict
               (:error (await (invoke! restarted fixture actor (assoc request :github/body "changed") fetch (constantly true))))))
        (is (= :unauthenticated
               (:error (await (invoke! restarted fixture actor request fetch (constantly false))))))
        (await (queue/submit!
                restarted "synthetic"
                (fn [lg] (identity/revoke-credential lg (state/fold lg) root actor "github-app" ((:ids fixture))))))
        (is (= :forbidden (:error (await (invoke! restarted fixture actor request fetch (constantly true))))))
        (is (= 6 (count @calls)))))))

(defn- ^:async verify-unknown-and-checkpoint-failure* []
  (with-redefs [app/read-key-file (fn [_ _] "fixture-pem")
                app/app-jwt (fn [& _] "fixture-jwt")]
    (doseq [outcome [:timeout :foreign :intent-failure :receipt-failure :revoked]]
      (let [{:keys [durable storage fail-size] :as fixture} (fixture-state nil)
            calls (atom [])
            fetch (provider durable calls outcome)
            writer (queue/create storage)
            before (count @durable)
            active? (constantly (not= :revoked outcome))]
        (case outcome
          :intent-failure (reset! fail-size (+ before 2))
          :receipt-failure (reset! fail-size (+ before 3))
          nil)
        (is (= (case outcome :revoked :unauthenticated :intent-failure :github-unavailable :comment-outcome-unknown)
               (:error (await (invoke! writer fixture actor request fetch active?)))))
        (if (contains? #{:intent-failure :revoked} outcome)
          (do (is (= before (count @durable))) (is (= 5 (count @calls))))
          (do
            (is (= :axxium.github/comment-intended (:event/type (last @durable))))
            (is (= :comment-outcome-unknown
                   (:error (await (invoke! (queue/create storage) fixture actor request fetch (constantly true))))))
            (is (= 6 (count @calls)))))))))

(defn- ^:async verify-authority-at-provider-send* []
  (with-redefs [app/read-key-file (fn [_ _] "fixture-pem")
                app/app-jwt (fn [& _] "fixture-jwt")]
    (doseq [loss [:session-revoked :lease-expired]]
      (let [lease-until (when (= :lease-expired loss) fixture/t2)
            {:keys [clock durable storage] :as fixture} (fixture-state lease-until)
            session-active? (atom true)
            calls (atom [])
            fetch (provider durable calls :ok)
            args (cond-> request lease-until (assoc :actor/did leased-actor))
            append! (:append! storage)
            storage (assoc storage :append!
                           (fn [path before after]
                             (let [saved (append! path before after)]
                               (when (= :axxium.github/comment-intended (:event/type (last saved)))
                                 (case loss
                                   :session-revoked (reset! session-active? false)
                                   :lease-expired (reset! clock lease-until)))
                               saved)))]
        (is (= :comment-outcome-unknown
               (:error (await (invoke! (queue/create storage) fixture root args fetch #(deref session-active?))))))
        (is (= :axxium.github/comment-intended (:event/type (last @durable))))
        (is (= 5 (count @calls)) "Provider traffic is limited to credential setup and cleanup")
        (reset! session-active? true)
        (is (= (if lease-until :forbidden :comment-outcome-unknown)
               (:error (await (invoke! (queue/create storage) fixture root args fetch (constantly true))))))
        (is (= 5 (count @calls)))))))

(deftest managed-comment-is-scoped-attributed-and-deduplicated
  (async done
    (-> (verify-managed-comment*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))

(deftest managed-comment-ambiguity-never-posts-again
  (async done
    (-> (verify-unknown-and-checkpoint-failure*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))

(deftest managed-comment-rechecks-authority-after-intent-is-durable
  (async done
    (-> (verify-authority-at-provider-send*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))
