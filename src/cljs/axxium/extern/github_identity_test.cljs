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

(def err "did:web:axxium.example:actors:err")
(def grok "did:web:axxium.example:actors:grok-bot")
(def clio "did:web:axxium.example:actors:clio-bot")
(def review "did:web:axxium.example:actors:pr-review-bot")
(def sub "did:web:axxium.example:actors:clio-helper")

(def root-policy
  {:policy/repos #{"*"}
   :policy/permissions {"contents" "write" "pull_requests" "write" "issues" "write" "checks" "read"}
   :policy/actions #{:read :comment :review :approve :push :merge}
   :policy/forbid #{:force-push}})

(def clio-policy
  {:policy/repos #{"riatzukiza/clio"}
   :policy/permissions {"contents" "write" "pull_requests" "write"}
   :policy/actions #{:read :comment :push}
   :policy/forbid #{:force-push :approve-own :merge-own}})

(def review-policy
  {:policy/repos #{"riatzukiza/clio" "riatzukiza/axxium"}
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
        s1 (step ids s0 gi/register-root err "Err" root-policy)
        s2 (step ids s1 gi/provision-actor err grok "Grok Bot" root-policy)
        s3 (step ids s2 gi/provision-actor err clio "Clio Bot" clio-policy)
        s4 (step ids s3 gi/provision-actor err review "PR Review Bot" review-policy)]
    {:ids ids :lg (first s4) :st (second s4)}))

(deftest child-policy-is-within-parent
  (is (law/policy-within? clio-policy root-policy))
  (is (not (law/policy-within? root-policy clio-policy)))
  (is (not (law/policy-within? (assoc clio-policy :policy/forbid #{}) root-policy))
      "a child cannot drop a parent's forbid")
  (is (not (law/policy-within? (assoc clio-policy :policy/repos #{"*"}) review-policy)))
  (let [{:keys [ids lg st]} (tree)
        [_ _ wide] (step ids [lg st] gi/provision-actor clio sub "helper"
                         (assoc clio-policy :policy/permissions {"contents" "admin"}))
        [_ st' ok] (step ids [lg st] gi/provision-actor clio sub "helper"
                         (assoc clio-policy :policy/actions #{:read}))]
    (is (nil? (:effect wide)) "child cannot exceed parent")
    (is (some? (:effect ok)))
    (is (= [clio err] (gi/ancestors-of st' sub)))
    (is (policy/decision-before-effect? (:ledger ok)))))

(deftest siblings-cannot-touch-siblings
  (let [{:keys [ids lg st]} (tree)
        [_ _ s] (step ids [lg st] gi/suspend clio review)
        [_ _ c] (step ids [lg st] gi/provision-credential clio review "github-app" :github-app
                      {:credential/sealed "x"} {})
        [_ st' own] (step ids [lg st] gi/suspend err review)]
    (is (nil? (:effect s)))
    (is (nil? (:effect c)))
    (is (some? (:effect own)) "the parent can")
    (is (not (gi/effective? st' review)))
    (testing "an actor cannot suspend itself out from under its parent's control"
      (let [[_ _ self] (step ids [lg st] gi/resume review review)] (is (nil? (:effect self)))))))

(deftest suspended-parent-cascades
  (let [{:keys [ids lg st]} (tree)
        [lg st] (step ids [lg st] gi/provision-actor clio sub "helper" (assoc clio-policy :policy/actions #{:read}))
        [lg st] (step ids [lg st] gi/suspend err clio)]
    (is (not (gi/effective? st sub)) "a suspended parent suspends its subtree")
    (is (= :active (gi/status-of st sub)) "without rewriting the child")
    (let [[_ _ out] (step ids [lg st] gi/provision-actor clio "did:web:axxium.example:actors:x" "x"
                          (assoc clio-policy :policy/actions #{:read}))]
      (is (nil? (:effect out)) "a suspended actor cannot provision"))
    (let [[_ st _] (step ids [lg st] gi/resume err clio)]
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
       (respond 201 {:id 9001 :slug "clio-bot" :pem "PEM-FROM-GITHUB" :client_id "Iv1"})

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
          clio-key (rsa) grok-key (rsa)
          dir (fs/mkdtempSync (path/join (os/tmpdir) "axx-gh-"))
          grok-file (path/join dir "grokbot-foresight.pem")
          _ (fs/writeFileSync grok-file (:pem grok-key))
          calls (atom [])
          deps {:fetch (fake-github {"5572746" (:public grok-key) "9001" (:public clio-key)} calls)
                :token-key key :key-dir dir :cache (atom {}) :now (constantly 1e12) :ids ids}
          c (fn [lg actor tool args] (mcp/call (assoc deps :ledger lg) {:actor actor} tool args))
          clio-scope {:scope/repos ["riatzukiza/clio"] :scope/permissions {"contents" "write"}}]
      (-> (c lg err "credential_provision"
             {:actor/did clio :credential/label "github-app" :credential/pem (:pem clio-key)
              :github/app-id 9001 :github/app-slug "clio-bot" :github/installation-id 77})
          (.then (fn [{lg :ledger e :error}]
                   (is (nil? e))
                   (is (not (str/includes? (pr-str lg) "PRIVATE KEY")) "no plaintext PEM in the ledger")
                   (c lg err "credential_provision"
                      {:actor/did grok :credential/label "github-app" :credential/key-file grok-file
                       :github/app-id 5572746 :github/app-slug "grokbot-foresight"
                       :github/installation-id 177661475})))
          (.then (fn [{lg :ledger e :error}]
                   (is (nil? e) "grokbot-foresight imported in place as Grok Bot's credential")
                   (-> (js/Promise.all
                        #js [(c lg clio "token_for" (assoc clio-scope :actor/did clio :credential/label "github-app"))
                             (c lg clio "token_for" (assoc clio-scope :actor/did clio :credential/label "github-app"))
                             (c lg clio "token_for" {:actor/did clio :credential/label "github-app"
                                                     :scope/repos ["riatzukiza/axxium"] :scope/permissions {}})
                             (c lg review "token_for" (assoc clio-scope :actor/did clio :credential/label "github-app"))
                             (c lg grok "token_for" {:actor/did grok :credential/label "github-app"
                                                     :scope/repos ["riatzukiza/knoxx"]
                                                     :scope/permissions {"pull_requests" "write"}})])
                       (.then (fn [rs] [lg (vec rs)])))))
          (.then (fn [[lg [a b out-of-policy sibling g]]]
                   (is (= "clio-bot[bot]" (get-in a [:result :github/actor])))
                   (is (= (law/attribution-marker clio) (get-in a [:result :axxium/marker])))
                   (is (str/starts-with? (get-in a [:result :token]) "ghs_"))
                   (is (= :forbidden (:error out-of-policy)) "G4: scope within policy")
                   (is (= :forbidden (:error sibling)) "a sibling cannot mint for a sibling")
                   (is (= "grokbot-foresight[bot]" (get-in g [:result :github/actor])))
                   (let [mints (filter #(str/includes? (second %) "/access_tokens") @calls)]
                     (is (= 3 (count mints)) "second identical request hit the cache")
                     (is (str/includes? (nth (first mints) 2) "\"repositories\":[\"clio\"]")))
                   (is (= lg (edn-lines/import-lines (edn-lines/export-lines lg))))
                   (-> (c lg err "credential_revoke" {:actor/did clio :credential/label "github-app"})
                       (.then (fn [{lg :ledger}]
                                (is (nil? (get-in (gi/credential (state/fold lg) clio "github-app")
                                                  [:resource/location :credential/sealed])))
                                (c lg clio "token_for" (assoc clio-scope :actor/did clio
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
  (is (not (law/action-allowed? clio-policy :approve {:own? false}))))

(deftest manifest-conversion
  (async done
    (-> (gh/convert-manifest (fake-github {} (atom [])) "good")
        (.then (fn [app] (is (= "clio-bot" (:slug app))) (is (= 9001 (:id app)))))
        (.catch (fn [e] (is false (pr-str e))))
        (.finally done))))
