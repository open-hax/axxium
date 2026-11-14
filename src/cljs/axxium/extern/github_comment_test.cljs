(ns axxium.extern.github-comment-test
  (:require [axxium.domain.github-comment :as comment]
            [axxium.domain.github-identity :as identity]
            [axxium.domain.kernel-fixture :as fixture]
            [axxium.domain.state :as state]
            [axxium.extern.github-app :as app]
            [axxium.extern.github-identity-mcp :as mcp]
            [axxium.extern.ledger-queue :as queue]
            [axxium.law.github-identity :as law]
            [cljs.test :refer [async deftest is]]))

(def root "did:web:example.invalid:actors:owner")
(def actor (str root ":codex"))
(def leased-actor (str actor ":session"))
(def outsider (str root ":other"))
(def repo "example/widgets")
(def request {:actor/did actor :credential/label "github-app" :github/repo repo :github/number 42
              :github/body "Synthetic comment" :github/request-id "00000000-0000-4000-8000-000000000007"})
(def policy {:policy/repos #{repo}
             :policy/permissions {"issues" "write" "pull_requests" "write"}
             :policy/actions #{:read :comment}
             :policy/forbid #{:force-push}})

(defn- fixture-state
  ([lease-until] (fixture-state lease-until (:policy/permissions policy)))
  ([lease-until permissions]
  (let [policy (assoc policy :policy/permissions permissions)
        counter (atom 100)
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
    {:durable durable :storage storage :fail-size fail-size :ids ids :clock clock})))

(defn- invoke! [writer {:keys [ids]} initiator args fetch reauthorize!]
  (queue/submit-checkpointed!
   writer "synthetic" (fn [lg checkpoint!]
                         (mcp/call {:ledger lg :checkpoint! checkpoint! :reauthorize! reauthorize!
                                    :fetch fetch :ids ids :cache (atom {}) :key-dir "/synthetic"}
                                   {:actor initiator} "github_comment" args))))

(defn- response
  ([status value] (response status value nil))
  ([status value request-id]
   (js/Promise.resolve #js {:status status :ok (< 199 status 300)
                            :headers #js {:get (fn [header] (when (= "x-github-request-id" header) request-id))}
                            :json (fn [] (js/Promise.resolve (clj->js value)))})))

(defn- provider
  ([durable calls outcome] (provider durable calls outcome {}))
  ([durable calls outcome {:keys [target install-permissions read-permissions]
                            :or {target :pull-request install-permissions {:issues "write" :pull_requests "write"}
                                 read-permissions {:issues "read" :pull_requests "read"}}}]
  (fn [url options]
    (let [method (.-method options)
          auth (.-authorization (.-headers options))
          payload (some-> (.-body options) js/JSON.parse (js->clj :keywordize-keys true))]
      (swap! calls conj [method url])
      (cond
        (= ["GET" "https://api.github.com/app/installations/78"] [method url])
        (response 200 {:id 78 :app_id 56 :app_slug (if (= :wrong-app outcome) "different-app" "codex-test")
                       :target_id 12 :target_type "User"
                       :account {:id 12 :login "example"} :permissions install-permissions})

        (= ["POST" "https://api.github.com/app/installations/78/access_tokens"] [method url])
        (let [perms (dissoc (:permissions payload) :metadata)
              metadata? (empty? perms)
              read? (some #{"read"} (vals perms))
              target-permission (if (= :issue target) :issues :pull_requests)]
          (is (= (cond
                   metadata? {:repositories ["widgets"] :permissions {:metadata "read"}}
                   read? {:repository_ids [90] :permissions (assoc read-permissions :metadata "read")}
                   :else {:repository_ids [90] :permissions {:metadata "read" target-permission "write"}})
                 payload))
          (response 201 {:token (cond metadata? "fixture-meta" read? "fixture-read" :else "fixture-write")
                         :expires_at "2099-01-01T00:00:00Z" :permissions (:permissions payload)
                         :repositories [{:id 90 :full_name repo :owner {:id 12 :login "example"}}]}))

        (= ["GET" "https://api.github.com/repos/example/widgets/issues/42"] [method url])
        (do
          (is (= "Bearer fixture-read" auth))
          (response 200 (cond-> {:number 42 :url url
                                :repository_url (if (= :wrong-target outcome)
                                                  "https://api.github.com/repos/foreign/widgets"
                                                  "https://api.github.com/repos/example/widgets")}
                          (= :pull-request target) (assoc :pull_request {:url "https://api.github.com/repos/example/widgets/pulls/42"}))))

        (= ["GET" "https://api.github.com/repos/example/widgets/pulls/42"] [method url])
        (do
          (is (= "Bearer fixture-read" auth))
          (if (= :issue target)
            (response 404 {})
            (response 200 {:number 42 :url url
                           :issue_url "https://api.github.com/repos/example/widgets/issues/42"
                           :base {:repo {:url (if (= :wrong-target outcome)
                                           "https://api.github.com/repos/foreign/widgets"
                                           "https://api.github.com/repos/example/widgets")}}})))

        (= ["POST" "https://api.github.com/repos/example/widgets/issues/42/comments"] [method url])
        (do
          (is (= ["Bearer fixture-write" {:body "Synthetic comment"}] [auth payload]))
          (is (= :axxium.github/comment-intended (:event/type (last @durable))))
          (is (= :axxium.policy/decided (:event/type (nth @durable (- (count @durable) 2)))))
          (is (not (re-find #"Synthetic comment|fixture-write" (pr-str @durable))))
          (case outcome
            :timeout (js/Promise.reject (js/Error. "synthetic timeout"))
            :refused (response 403 {:secret "synthetic-private-body"} "c0de:1234:beef")
            :invalid-header (response 422 {:secret "synthetic-private-body"} "not-a-github-id")
            :provider-unknown (response 502 {:secret "synthetic-private-body"} "C0DE:502:FEED")
            (response 201 {:id 51 :body (:body payload)
                           :html_url (str "https://github.com/example/widgets/"
                                          (if (= :issue target) "issues/" "pull/") "42#issuecomment-51")
                           :issue_url "https://api.github.com/repos/example/widgets/issues/42"
                           :user {:login (if (= :foreign outcome) "foreign[bot]" "codex-test[bot]") :type "Bot" :id 99}
                           :performed_via_github_app {:id 56 :slug "codex-test"}} "c0de:201:feed")))

        (= ["DELETE" "https://api.github.com/installation/token"] [method url])
        (do (is (contains? #{"Bearer fixture-meta" "Bearer fixture-read" "Bearer fixture-write"} auth)) (response 204 {}))

        :else (js/Promise.reject (js/Error. "unexpected provider endpoint")))))))

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
        (is (= 9 (count @calls)))
        (is (= out (await (invoke! restarted fixture root request fetch (constantly true)))))
        (is (= :comment-request-conflict
               (:error (await (invoke! restarted fixture actor (assoc request :github/body "changed") fetch (constantly true))))))
        (is (= :unauthenticated
               (:error (await (invoke! restarted fixture actor request fetch (constantly false))))))
        (await (queue/submit!
                restarted "synthetic"
                (fn [lg] (identity/revoke-credential lg (state/fold lg) root actor "github-app" ((:ids fixture))))))
        (is (= :forbidden (:error (await (invoke! restarted fixture actor request fetch (constantly true))))))
        (is (= 9 (count @calls)))))))

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
          (do (is (= before (count @durable))) (is (= (if (= outcome :revoked) 6 8) (count @calls))))
          (do
            (is (= (if (= :foreign outcome) :axxium.github/comment-unverified :axxium.github/comment-intended)
                   (:event/type (last @durable))))
            (is (= :comment-outcome-unknown
                   (:error (await (invoke! (queue/create storage) fixture actor request fetch (constantly true))))))
            (is (= 9 (count @calls)))))))))

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
        (is (= 8 (count @calls)) "Provider traffic is limited to credential setup and cleanup")
        (reset! session-active? true)
        (is (= (if lease-until :forbidden :comment-outcome-unknown)
               (:error (await (invoke! (queue/create storage) fixture root args fetch (constantly true))))))
        (is (= 8 (count @calls)))))))

(defn- ^:async verify-target-permissions* []
  (with-redefs [app/read-key-file (fn [_ _] "fixture-pem")
                app/app-jwt (fn [& _] "fixture-jwt")]
    (doseq [[target permissions installer read-probe outcome expected lookups]
            [[:pull-request {"pull_requests" "write"} {:pull_requests "write"} {:pull_requests "read"} :ok
              :pull-request ["/pulls/42"]]
             [:issue {"issues" "write"} {:issues "write"} {:issues "read"} :ok :issue ["/issues/42"]]
             [:issue (:policy/permissions policy) {:issues "write" :pull_requests "write"}
              {:issues "read" :pull_requests "read"} :ok :issue ["/pulls/42" "/issues/42"]]
             [:pull-request {"issues" "write"} {:issues "write" :pull_requests "write"} {:issues "read"} :ok
              :forbidden ["/issues/42"]]
             [:pull-request (:policy/permissions policy) {:issues "write" :pull_requests "write"}
              {:issues "read" :pull_requests "read"} :wrong-target :github-unavailable ["/pulls/42"]]]]
      (let [{:keys [durable storage] :as fixture} (fixture-state nil permissions)
            calls (atom [])
            fetch (provider durable calls outcome {:target target :install-permissions installer
                                                   :read-permissions read-probe})
            out (await (invoke! (queue/create storage) fixture actor request fetch (constantly true)))
            api-repo "https://api.github.com/repos/example/widgets"
            posts (filter #(= ["POST" (str api-repo "/issues/42/comments")] %) @calls)]
        (is (= expected (or (:error out) (get-in out [:result :github/target-kind]))))
        (is (= (mapv #(vector "GET" (str api-repo %)) lookups)
               (vec (filter #(and (= "GET" (first %)) (.startsWith (second %) api-repo)) @calls))))
        (is (= (if (:error out) 0 1) (count posts)))
        (when (:error out)
          (is (= 2 (count (filter #(= ["POST" "https://api.github.com/app/installations/78/access_tokens"] %)
                                 @calls))) "No write token is minted for a refused target"))))))

(defn- ^:async verify-provider-evidence* []
  (with-redefs [app/read-key-file (fn [_ _] "fixture-pem")
                app/app-jwt (fn [& _] "fixture-jwt")]
    (doseq [[outcome error event status provider-id]
            [[:refused :comment-provider-rejected :axxium.github/comment-rejected 403 "C0DE:1234:BEEF"]
             [:invalid-header :comment-provider-rejected :axxium.github/comment-rejected 422 nil]
             [:provider-unknown :comment-outcome-unknown :axxium.github/comment-unverified 502 "C0DE:502:FEED"]
             [:foreign :comment-outcome-unknown :axxium.github/comment-unverified 201 "C0DE:201:FEED"]]]
      (let [{:keys [durable storage] :as fixture} (fixture-state nil)
            calls (atom [])
            fetch (provider durable calls outcome)
            out (await (invoke! (queue/create storage) fixture actor request fetch (constantly true)))
            diagnostics (cond-> {:github/phase :comment-post :github/target-kind :pull-request :github/http-status status}
                          provider-id (assoc :github/provider-request-id provider-id))
            [intent response] (take-last 2 @durable)]
        (is (= {:error error :github/diagnostics diagnostics} out))
        (is (= event (:event/type response)))
        (is (= diagnostics (law/comment-provider-diagnostics (:event/body response))))
        (is (= [(:event/id intent)] (:event/causes response)))
        (is (not (re-find #"synthetic-private-body|not-a-github-id|Synthetic comment|fixture-write" (pr-str response))))
        (is (= out (await (invoke! (queue/create storage) fixture actor request fetch (constantly true)))))
        (is (= 9 (count @calls)) "An existing outcome never retries the provider POST")))
    (doseq [[policy-permissions expected]
            [[{"issues" "write"} :comment-outcome-unknown]
             [{"pull_requests" "write"} :forbidden]]]
      (let [{:keys [durable storage ids] :as fixture} (fixture-state nil policy-permissions)
            digest (mcp/digest (pr-str (into (sorted-map) request)))
            old-state (state/fold @durable)
            calls (atom [])]
        (swap! durable #(:ledger (comment/decision % old-state actor request digest true nil (ids))))
        (is (= :axxium.github/comment-intended (:event/type (last @durable))))
        (is (not (contains? (:event/body (last @durable)) :github/target-kind)))
        (is (= expected (:error
                         (await (invoke! (queue/create storage) fixture actor request (provider durable calls :ok)
                                         (constantly true))))))
        (is (empty? @calls) "Prior pending intents are handled without probing or reposting")))))

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

(deftest managed-comment-writes-only-the-verified-target-permission
  (async done
    (-> (verify-target-permissions*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))

(deftest managed-comment-preserves-safe-provider-evidence-without-reposting
  (async done
    (-> (verify-provider-evidence*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))
