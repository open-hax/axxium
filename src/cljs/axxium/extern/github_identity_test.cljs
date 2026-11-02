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
  [apps calls]
  (fn [url opts]
    (swap! calls conj [(.-method opts) url (some-> opts .-body)])
    (js/Promise.resolve
     (cond
       (str/includes? url "/app-manifests/good/conversions")
       (respond 201 {:id 9001 :slug "widgets-bot" :pem "PEM-FROM-GITHUB" :client_id "Iv1"})

       (re-find #"/app/installations/(\d+)/access_tokens" url)
       (let [[h p s] (str/split (aget (.-headers opts) "authorization") #"\.")
             jwt (str/replace h "Bearer " "")
             claims (js->clj (js/JSON.parse (.toString (js/Buffer.from p "base64url"))))
             pub (get apps (get claims "iss"))]
         (if (and pub (crypto/verify "RSA-SHA256" (js/Buffer.from (str jwt "." p))
                                     pub (js/Buffer.from s "base64url")))
           (respond 201 {:token (str "ghs_" (count @calls)) :expires_at "2099-01-01T00:00:00Z"})
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
          deps {:fetch (fake-github {"1001" (:public assistant-key) "9001" (:public widgets-key)} calls)
                :token-key key :key-dir dir :cache (atom {}) :now (constantly 1e12) :ids ids}
          c (fn [lg actor tool args] (mcp/call (assoc deps :ledger lg) {:actor actor} tool args))
          widgets-scope {:scope/repos ["example-org/widgets"] :scope/permissions {"contents" "write"}}]
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
                             (c lg widgets "token_for" (assoc widgets-scope :actor/did widgets :credential/label "github-app"))
                             (c lg widgets "token_for" {:actor/did widgets :credential/label "github-app"
                                                     :scope/repos ["example-org/website"] :scope/permissions {}})
                             (c lg review "token_for" (assoc widgets-scope :actor/did widgets :credential/label "github-app"))
                             (c lg assistant "token_for" {:actor/did assistant :credential/label "github-app"
                                                     :scope/repos ["other-org/private"]
                                                     :scope/permissions {"pull_requests" "write"}})])
                       (.then (fn [rs] [lg (vec rs)])))))
          (.then (fn [[lg [a b out-of-policy sibling g]]]
                   (is (= "widgets-bot[bot]" (get-in a [:result :github/actor])))
                   (is (= (law/attribution-marker widgets) (get-in a [:result :axxium/marker])))
                   (is (str/starts-with? (get-in a [:result :token]) "ghs_"))
                   (is (= :forbidden (:error out-of-policy)) "G4: scope within policy")
                   (is (= :forbidden (:error sibling)) "a sibling cannot mint for a sibling")
                   (is (= "assistant-bot[bot]" (get-in g [:result :github/actor])))
                   (let [mints (filter #(str/includes? (second %) "/access_tokens") @calls)]
                     (is (= 3 (count mints)) "second identical request hit the cache")
                     (is (str/includes? (nth (first mints) 2) "\"repositories\":[\"widgets\"]")))
                   (is (= lg (edn-lines/import-lines (edn-lines/export-lines lg))))
                   (-> (c lg alice "credential_revoke" {:actor/did widgets :credential/label "github-app"})
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
