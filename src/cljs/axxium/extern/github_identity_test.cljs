(ns axxium.extern.github-identity-test
  "Bot identity tree, delegation policy and per-actor App tokens against a
   fake GitHub. No network."
  (:require [axxium.domain.github-identity :as gi]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.ledger :as ledger]
            [axxium.domain.policy :as policy]
            [axxium.domain.state :as state]
            [axxium.extern.github-app :as gh]
            [axxium.extern.github-identity-mcp :as mcp]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.github-identity :as law]
            [axxium.shape.ledger-edn :as edn-lines]
            [clojure.string :as str]
            [cljs.test :refer [deftest is async testing]]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def alice "did:web:axxium.example:actors:alice")
(def assistant "did:web:axxium.example:actors:assistant-bot")
(def widgets "did:web:axxium.example:actors:widgets-bot")
(def review "did:web:axxium.example:actors:pr-review-bot")
(def sub "did:web:axxium.example:actors:widgets-helper")

(def root-policy
  {:policy/repos #{"*"}
   :policy/permissions {"contents" "write" "pull_requests" "write" "issues" "write" "checks" "read"}
   :policy/actions #{:read :comment :review :approve :push :merge}
   :policy/forbid #{:force-push}})

(def widgets-policy
  {:policy/repos #{"example-org/widgets"}
   :policy/permissions {"contents" "write" "pull_requests" "write"}
   :policy/actions #{:read :comment :push}
   :policy/forbid #{:force-push :approve-own :merge-own}})

(def review-policy
  {:policy/repos #{"example-org/widgets" "example-org/website"}
   :policy/permissions {"pull_requests" "write"}
   :policy/actions #{:read :comment :review :approve}
   :policy/forbid #{:force-push :approve-own :merge-own}})

(defn- counter-ids []
  (let [n (atom 100)]
    (fn [] {:decision-id (fx/event-uuid (swap! n inc)) :effect-id (fx/event-uuid (swap! n inc)) :at fx/t1})))

(defn- step [ids [lg st] f & args]
  (let [out (apply f lg st (concat args [(ids)]))]
    [(:ledger out) (state/fold (:ledger out)) out]))

(defn- tree []
  (let [ids (counter-ids)
        s0 [ledger/empty-ledger (state/fold ledger/empty-ledger)]
        s1 (step ids s0 gi/register-root alice "Alice" root-policy)
        s2 (step ids s1 gi/provision-actor alice assistant "Assistant Bot" root-policy)
        s3 (step ids s2 gi/provision-actor alice widgets "Widgets Bot" widgets-policy)
        s4 (step ids s3 gi/provision-actor alice review "PR Review Bot" review-policy)]
    {:ids ids :lg (first s4) :st (second s4)}))

(deftest child-policy-is-within-parent
  (is (law/policy-within? widgets-policy root-policy))
  (is (not (law/policy-within? root-policy widgets-policy)))
  (is (not (law/policy-within? (assoc widgets-policy :policy/forbid #{}) root-policy))
      "a child cannot drop a parent's forbid")
  (is (not (law/policy-within? (assoc widgets-policy :policy/repos #{"*"}) review-policy)))
  (let [{:keys [ids lg st]} (tree)
        [_ _ wide] (step ids [lg st] gi/provision-actor widgets sub "helper"
                         (assoc widgets-policy :policy/permissions {"contents" "admin"}))
        [_ st' ok] (step ids [lg st] gi/provision-actor widgets sub "helper"
                         (assoc widgets-policy :policy/actions #{:read}))]
    (is (nil? (:effect wide)) "child cannot exceed parent")
    (is (some? (:effect ok)))
    (is (= [widgets alice] (gi/ancestors-of st' sub)))
    (is (policy/decision-before-effect? (:ledger ok)))))

(deftest siblings-cannot-touch-siblings
  (let [{:keys [ids lg st]} (tree)
        [_ _ s] (step ids [lg st] gi/suspend widgets review)
        [_ _ c] (step ids [lg st] gi/provision-credential widgets review "github-app" :github-app
                      {:credential/sealed "x"} {})
        [_ st' own] (step ids [lg st] gi/suspend alice review)]
    (is (nil? (:effect s)))
    (is (nil? (:effect c)))
    (is (some? (:effect own)) "the parent can")
    (is (not (gi/effective? st' review)))
    (testing "an actor cannot suspend itself out from under its parent's control"
      (let [[_ _ self] (step ids [lg st] gi/resume review review)] (is (nil? (:effect self)))))))

(deftest credential-id-collision-cannot-replace-another-owners-credential
  (let [{:keys [ids lg st]} (tree)
        victim (str sub ":build")
        [lg st] (step ids [lg st] gi/provision-actor widgets sub "Widgets Helper" widgets-policy)
        [lg st] (step ids [lg st] gi/provision-actor review victim "Review Helper" review-policy)
        [lg st original] (step ids [lg st] gi/provision-credential review victim "github-app" :github-app
                               {:credential/sealed "victim-secret"} {})
        [_ after attempted] (step ids [lg st] gi/provision-credential widgets sub "build:github-app" :github-app
                                  {:credential/sealed "replacement-secret"} {})]
    (is (some? (:effect original)))
    (is (not (gi/may-manage? st widgets victim)))
    (is (= (law/credential-resource-id victim "github-app")
           (law/credential-resource-id sub "build:github-app")))
    (is (nil? (:effect attempted)))
    (is (false? (get-in attempted [:decision :event/body :decision/allow?])))
    (is (= (gi/credential st victim "github-app") (gi/credential after victim "github-app")))
    (is (nil? (gi/credential after sub "build:github-app")))))

(deftest suspended-parent-cascades
  (let [{:keys [ids lg st]} (tree)
        [lg st] (step ids [lg st] gi/provision-actor widgets sub "helper" (assoc widgets-policy :policy/actions #{:read}))
        [lg st] (step ids [lg st] gi/suspend alice widgets)]
    (is (not (gi/effective? st sub)) "a suspended parent suspends its subtree")
    (is (= :active (gi/status-of st sub)) "without rewriting the child")
    (let [[_ _ out] (step ids [lg st] gi/provision-actor widgets "did:web:axxium.example:actors:x" "x"
                          (assoc widgets-policy :policy/actions #{:read}))]
      (is (nil? (:effect out)) "a suspended actor cannot provision"))
    (let [[_ st _] (step ids [lg st] gi/resume alice widgets)]
      (is (gi/effective? st sub)))))

(defn- rsa []
  (let [kp (crypto/generateKeyPairSync "rsa" #js {:modulusLength 2048})]
    {:pem (.export (.-privateKey kp) #js {:type "pkcs8" :format "pem"})
     :public (.-publicKey kp)}))

(defn- respond [status body]
  #js {:ok (< status 400) :status status :json (fn [] (js/Promise.resolve (clj->js body)))})

(defn- fake-github
  "Verifies the app JWT against `apps` {app-id public-key}; records calls."
  [apps calls & [installation-grants]]
  (fn [url opts]
    (swap! calls conj [(.-method opts) url (some-> opts .-body)])
    (js/Promise.resolve
     (cond
       (str/includes? url "/app-manifests/good/conversions")
       (respond 201 {:id 9001 :slug "widgets-bot" :pem "PEM-FROM-GITHUB" :client_id "Iv1"})

       (re-find #"/app/installations/(\d+)(?:/access_tokens)?$" url)
       (let [[_ installation-id] (re-find #"/app/installations/(\d+)" url)
             [h p s] (str/split (aget (.-headers opts) "authorization") #"\.")
             jwt (str/replace h "Bearer " "")
             claims (js->clj (js/JSON.parse (.toString (js/Buffer.from p "base64url"))))
             pub (get apps (get claims "iss"))
             [app-id owner repo repo-id] (get {"77" [9001 "example-org" "widgets" 10077]
                                               "2002" [1001 "other-org" "private" 12002]}
                                              installation-id)
             account {:id app-id :login owner}
             permissions (get (some-> installation-grants deref) installation-id
                              {:contents "write" :pull_requests "write" :metadata "read"})]
         (if (and pub (crypto/verify "RSA-SHA256" (js/Buffer.from (str jwt "." p))
                                     pub (js/Buffer.from s "base64url")))
           (if (= "GET" (.-method opts))
             (respond 200 {:id (js/Number installation-id) :app_id app-id :target_id app-id
                           :target_type "Organization" :account account :permissions permissions})
             (let [scope (js->clj (js/JSON.parse (.-body opts)) :keywordize-keys true)]
               (if (or (= [repo] (:repositories scope)) (= [repo-id] (:repository_ids scope)))
                 (respond 201 {:token (str "ghs_" (count @calls)) :expires_at "2099-01-01T00:00:00Z"
                               :permissions (:permissions scope)
                               :repositories [{:id repo-id :full_name (str owner "/" repo) :owner account}]})
                 (respond 422 {}))))
           (respond 401 {})))

       :else (respond 404 {})))))

(deftest per-actor-tokens-are-scoped-cached-and-attributed
  (async done
    (let [{:keys [ids lg]} (tree)
          key (crypto/randomBytes 32)
          widgets-key (rsa) assistant-key (rsa)
          dir (fs/mkdtempSync (path/join (os/tmpdir) "axx-gh-"))
          assistant-file (path/join dir "assistant-bot.pem")
          _ (fs/writeFileSync assistant-file (:pem assistant-key))
          calls (atom [])
          installation-grants (atom {})
          deps {:fetch (fake-github {"1001" (:public assistant-key) "9001" (:public widgets-key)}
                                   calls installation-grants)
                :token-key key :key-dir dir :cache (atom {}) :now (constantly 1e12) :ids ids}
          c (fn [lg actor tool args] (mcp/call (assoc deps :ledger lg) {:actor actor} tool args))
          widgets-scope {:scope/repos ["example-org/widgets"] :scope/permissions {"contents" "read"}}]
      (-> (c lg alice "credential_provision"
             {:actor/did widgets :credential/label "github-app" :credential/pem (:pem widgets-key)
              :github/app-id 9001 :github/app-slug "widgets-bot" :github/installation-id 77})
          (.then (fn [{lg :ledger e :error}]
                   (is (nil? e))
                   (is (not (str/includes? (pr-str lg) "PRIVATE KEY")) "no plaintext PEM in the ledger")
                   (c lg alice "credential_provision"
                      {:actor/did assistant :credential/label "github-app" :credential/key-file assistant-file
                       :github/app-id 1001 :github/app-slug "assistant-bot"
                       :github/installation-id 2002})))
          (.then (fn [{lg :ledger e :error}]
                   (is (nil? e) "assistant-bot imported in place as Grok Bot's credential")
                   (-> (js/Promise.all
                        #js [(c lg widgets "token_for" (assoc widgets-scope :actor/did widgets :credential/label "github-app"))
                             (c lg widgets "token_for" {:actor/did widgets :credential/label "github-app"
                                                     :scope/repos ["example-org/website"] :scope/permissions {}})
                             (c lg review "token_for" (assoc widgets-scope :actor/did widgets :credential/label "github-app"))
                             (c lg assistant "token_for" {:actor/did assistant :credential/label "github-app"
                                                     :scope/repos ["other-org/private"]
                                                     :scope/permissions {"pull_requests" "read"}})])
                       (.then (fn [rs] [lg (vec rs)])))))
          (.then (fn [[lg [a out-of-policy sibling g]]]
                   (is (= "widgets-bot[bot]" (get-in a [:result :github/actor])))
                   (is (= (law/attribution-marker widgets) (get-in a [:result :axxium/marker])))
                   (is (str/starts-with? (get-in a [:result :token]) "ghs_"))
                   (is (= :forbidden (:error out-of-policy)) "G4: scope within policy")
                   (is (= :forbidden (:error sibling)) "a sibling cannot mint for a sibling")
                   (is (= "assistant-bot[bot]" (get-in g [:result :github/actor])))
                   (is (= lg (edn-lines/import-lines (edn-lines/export-lines lg))))
                   (-> (c lg widgets "token_for" (assoc widgets-scope :actor/did widgets :credential/label "github-app"))
                       (.then (fn [cached]
                                (is (= (get-in a [:result :token]) (get-in cached [:result :token])))
                                (is (true? (get-in cached [:result :cached?])))
                                (let [mints (->> @calls
                                                 (filter #(str/includes? (second %) "/access_tokens"))
                                                 (map #(js->clj (js/JSON.parse (nth % 2)) :keywordize-keys true)))]
                                  (is (= 5 (count mints)) "recheck metadata while reusing the final token")
                                  (is (= #{[10077] [12002]} (set (keep :repository_ids mints))))
                                  (is (every? #(= {:metadata "read"} (:permissions %))
                                              (filter :repositories mints))))
                                (reset! installation-grants {"77" {:metadata "read"}})
                                (c lg widgets "token_for" (assoc widgets-scope :actor/did widgets
                                                                  :credential/label "github-app"))))
                       (.then (fn [downgraded]
                                (is (= :installation-scope-mismatch (:error downgraded)))
                                (is (nil? (:result downgraded)))
                                (is (= ["GET" (str gh/api "/app/installations/77") nil] (peek @calls))
                                    "a downgraded installation cannot return its cached token or mint another")
                                (c lg alice "credential_revoke" {:actor/did widgets :credential/label "github-app"})))
                       (.then (fn [{lg :ledger}]
                                (is (nil? (get-in (gi/credential (state/fold lg) widgets "github-app")
                                                  [:resource/location :credential/sealed])))
                                (c lg widgets "token_for" (assoc widgets-scope :actor/did widgets
                                                              :credential/label "github-app")))))))
          (.then (fn [after-revoke] (is (= :forbidden (:error after-revoke)))))
          (.catch (fn [e] (is false (pr-str e))))
          (.finally done)))))

(deftest imported-key-must-live-under-key-dir
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "axx-gh-"))
        outside (path/join (os/tmpdir) (str "outside-" (random-uuid) ".pem"))]
    (fs/writeFileSync outside "x")
    (is (nil? (gh/read-key-file dir outside)))
    (is (nil? (gh/read-key-file "" outside)))))

(deftest manifest-and-self-approval
  (let [m (law/app-manifest {:public-base-url "https://axxium.example" :actor-name "PR Review Bot"
                             :policy review-policy})]
    (is (= "pr-review-bot" (:name m)))
    (is (= false (:public m)))
    (is (= {"metadata" "read" "pull_requests" "write"} (:default_permissions m)))
    (is (= "https://axxium.example/api/github/manifest/callback" (:redirect_url m))))
  (is (law/action-allowed? review-policy :approve {:own? false}))
  (is (not (law/action-allowed? review-policy :approve {:own? true})) "never approve its own PR")
  (is (not (law/action-allowed? widgets-policy :approve {:own? false}))))

(deftest manifest-conversion
  (async done
    (-> (gh/convert-manifest (fake-github {} (atom [])) "good")
        (.then (fn [app] (is (= "widgets-bot" (:slug app))) (is (= 9001 (:id app)))))
        (.catch (fn [e] (is false (pr-str e))))
        (.finally done))))

(defn- ^:async same-name-cross-owner* []
  (let [{:keys [ids lg st]} (tree)
        key (crypto/randomBytes 32)
        {:keys [pem]} (rsa)
        {credential-ledger :ledger} (gi/provision-credential
                                     lg st alice widgets "github-app" :github-app
                                     {:credential/sealed (tc/seal key widgets pem)}
                                     {:github/app-id 9001 :github/app-slug "widgets-bot"
                                      :github/installation-id 77} (ids))
        calls (atom [])
        cache (atom {})
        fetch (fn [url opts]
                (swap! calls conj [(.-method opts) url])
                (js/Promise.resolve
                 (if (= url (str gh/api "/app/installations/77"))
                   (respond 200 {:id 77 :app_id 9001 :target_id 123 :target_type "Organization"
                                 :account {:login "other-org" :id 123}
                                 :permissions {:contents "read"}})
                   (respond 201 {:token "synthetic-cross-owner-token" :expires_at "2099-01-01T00:00:00Z"}))))
        out (await (mcp/call {:fetch fetch :token-key key :cache cache :now (constantly 1e12)
                             :ids ids :ledger credential-ledger}
                            {:actor widgets} "token_for"
                            {:actor/did widgets :credential/label "github-app"
                             :scope/repos ["example-org/widgets"] :scope/permissions {"contents" "read"}}))]
    (is (= :installation-scope-mismatch (:error out)))
    (is (nil? (:result out)))
    (is (= [["GET" (str gh/api "/app/installations/77")]] @calls)
        "an installation for a different owner never reaches token creation")
    (is (empty? @cache))
    (is (> (count (:ledger out)) (count credential-ledger)) "the policy decision is retained")))

(deftest same-repository-name-under-another-owner-never-mints
  (async done
    (-> (same-name-cross-owner*)
        (.catch (fn [e] (is false (pr-str e))))
        (.finally done))))

(def ^:private installation
  {:id 77 :app_id 9001 :target_id 123 :target_type "Organization"
   :account {:id 123 :login "example-org"} :permissions {:contents "read"}})

(defn- contents-read-scope [repos]
  {:scope/repos repos :scope/permissions {"contents" "read"}})

(defn- repository [id name]
  {:id id :full_name (str "example-org/" name) :owner (:account installation)})

(defn- token-response [perms]
  {:token "synthetic" :expires_at "2099-01-01T00:00:00Z" :permissions perms})

(defn- ^:async scope-rejection [operation]
  (try
    (await (operation))
    nil
    (catch :default error (:axxium/error (ex-data error)))))

(defn- ^:async scope-pagination* []
  (let [rows (mapv #(repository % (str "repo-" %)) (range 1 102))
        names (mapv :full_name rows)
        calls (atom [])
        fetch (fn [url opts]
                (let [body (some-> (.-body opts) js/JSON.parse (js->clj :keywordize-keys true))]
                  (is (= "error" (.-redirect opts)))
                  (swap! calls conj [(.-method opts) url body])
                  (js/Promise.resolve
                   (respond 200
                            (cond
                              (= url (str gh/api "/app/installations/77")) installation
                              (:repositories body) (token-response {:metadata "read"})
                              (str/ends-with? url "page=1") {:total_count 101 :repositories (subvec rows 0 100)}
                              (str/ends-with? url "page=2") {:total_count 101 :repositories (subvec rows 100)}
                              :else (assoc (token-response {:contents "read"}) :repositories rows))))))
        signal (js/AbortSignal.timeout 5000)
        proof (await (gh/verify-installation-scope fetch "synthetic-jwt" 9001 "77"
                                                   (contents-read-scope names) signal))
        token (await (gh/mint-installation-token fetch "synthetic-jwt" proof {"contents" "read"} signal))]
    (is (= (vec (range 1 102)) (:repository-ids proof)))
    (is (= "synthetic" (:token token)))
    (is (= [{:repositories (mapv #(str "repo-" %) (range 1 102)) :permissions {:metadata "read"}}
            {:repository_ids (vec (range 1 102)) :permissions {:contents "read" :metadata "read"}}]
           (mapv #(nth % 2) (filter #(= "POST" (first %)) @calls))))
    (is (= [(str gh/api "/installation/repositories?per_page=100&page=1")
            (str gh/api "/installation/repositories?per_page=100&page=2")]
           (mapv second (filter #(str/includes? (second %) "/installation/repositories?") @calls))))))

(deftest scoped-installation-resolves-repositories-across-pages
  (async done
    (-> (scope-pagination*)
        (.catch (fn [e] (is false (pr-str e))))
        (.finally done))))

(defn- ^:async malformed-provider-scope* []
  (let [good-repo (repository 456 "widgets")
        good-token (assoc (token-response {:metadata "read"}) :repositories [good-repo])
        signal (js/AbortSignal.timeout 5000)
        mock-fetch (fn [found installation-token final-token calls]
                     (fn [url opts]
                       (let [body (some-> (.-body opts) js/JSON.parse (js->clj :keywordize-keys true))]
                         (swap! calls conj url)
                         (js/Promise.resolve
                          (respond 200 (cond
                                         (= "GET" (.-method opts)) found
                                         (:repositories body) installation-token
                                         :else final-token))))))
        scope (contents-read-scope ["example-org/widgets"])]
    (doseq [bad-names [[] ["example-org"] (mapv #(str "example-org/" %) (range 501))]]
      (let [calls (atom [])
            fetch (mock-fetch installation good-token good-token calls)]
        (is (= :installation-scope-mismatch
               (await (scope-rejection #(gh/verify-installation-scope
                                          fetch "jwt" 9001 77 (contents-read-scope bad-names) signal)))))
        (is (empty? @calls))))
    (doseq [bad-installation [(assoc installation :app_id 9002)
                              (assoc installation :id 78)
                              (assoc installation :target_type "Enterprise")
                              (assoc installation :target_id 124)
                              (assoc installation :permissions {:metadata "read"})
                              (assoc installation :suspended_at "2026-11-01T00:00:00Z")]]
      (let [calls (atom [])
            fetch (mock-fetch bad-installation good-token good-token calls)]
        (is (= :installation-scope-mismatch
               (await (scope-rejection #(gh/verify-installation-scope fetch "jwt" 9001 77 scope signal)))))
        (is (= 1 (count @calls)))))
    (doseq [bad-token [(assoc good-token :permissions {:contents "write"})
                       (assoc good-token :repositories [(assoc-in good-repo [:owner :id] 124)])
                       (assoc good-token :repositories [(assoc good-repo :full_name "example-org/renamed")])
                       (assoc good-token :repositories [(assoc good-repo :id 0)])
                       (assoc good-token :repositories [good-repo (repository 457 "extra")])]]
      (let [calls (atom [])
            fetch (mock-fetch installation bad-token good-token calls)]
        (is (= :installation-scope-mismatch
               (await (scope-rejection #(gh/verify-installation-scope fetch "jwt" 9001 77 scope signal)))))
        (is (= 2 (count @calls)))))
    (doseq [bad-final [(assoc good-token :permissions {:contents "write"})
                       (assoc good-token :permissions {:contents "read"}
                                        :repositories [(assoc good-repo :id 457)])]]
      (let [calls (atom [])
            fetch (mock-fetch installation good-token bad-final calls)
            proof (await (gh/verify-installation-scope fetch "jwt" 9001 77 scope signal))]
        (is (= :installation-scope-mismatch
               (await (scope-rejection #(gh/mint-installation-token fetch "jwt" proof {"contents" "read"} signal)))))
        (is (= 3 (count @calls)))))))

(deftest installation-mint-refuses-malformed-or-broadened-provider-scope
  (async done
    (-> (malformed-provider-scope*)
        (.catch (fn [e] (is false (pr-str e))))
        (.finally done))))
