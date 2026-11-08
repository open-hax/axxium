(ns axxium.extern.github-token-policy-test
  "The exported installation token must stay within the holder's action policy."
  (:require [axxium.domain.github-identity :as identity]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.state :as state]
            [axxium.extern.github-app :as app]
            [axxium.extern.github-identity-mcp :as mcp]
            [axxium.extern.token-crypto :as crypto]
            [clojure.string :as str]
            [cljs.test :refer [async deftest is]]
            ["node:crypto" :as node-crypto]))

(defn- ^:async exported-token-policy* []
  (let [root "did:web:axxium.example:actors:root"
        reader (str root ":reader")
        commenter (str root ":commenter")
        permissions {"pull_requests" "write" "contents" "write" "members" "read"}
        policy {:policy/repos #{"example/widgets"}
                :policy/permissions permissions
                :policy/actions #{:read :comment :push}
                :policy/forbid #{:force-push :approve-own :merge-own}}
        seed {:root {:id root :name "Root" :policy policy}
              :nodes [{:id reader :parent root :name "Reader"
                       :policy (assoc policy :policy/actions #{:read})}
                      {:id commenter :parent root :name "Commenter"
                       :policy (assoc policy :policy/actions #{:comment})}]}
        counter (atom 100)
        ids (fn [] {:decision-id (fx/event-uuid (swap! counter inc))
                    :effect-id (fx/event-uuid (swap! counter inc)) :at fx/t1})
        seeded (:ledger (identity/seed-ledger (identity/resolve-seed seed "axxium.example") ids))
        token-key (node-crypto/randomBytes 32)
        ledger (reduce (fn [ledger did]
                         (:ledger (identity/provision-credential
                                   ledger (state/fold ledger) root did "github-app" :github-app
                                   {:credential/sealed (crypto/seal token-key did "synthetic-pem")}
                                   {:github/app-id 42 :github/app-slug "synthetic" :github/installation-id 99}
                                   (ids))))
                       seeded [reader commenter])
        calls (atom [])
        fetch (fn [url opts]
                (let [body (some-> opts .-body)]
                  (swap! calls conj [url (.-method opts) body])
                  (js/Promise.resolve
                   #js {:ok true
                        :json (fn []
                                (js/Promise.resolve
                                 (if (str/ends-with? url "/access_tokens")
                                   #js {:token "synthetic-token"
                                        :expires_at "2099-01-01T00:00:00Z"
                                        :permissions (.-permissions (js/JSON.parse body))
                                        :repositories #js [#js {:id 456 :full_name "example/widgets"
                                                                :owner #js {:id 123 :login "example"}}]}
                                   #js {:id 99 :app_id 42 :target_id 123 :target_type "Organization"
                                        :account #js {:id 123 :login "example"}})))})))
        mint (fn [caller target requested]
               (mcp/call {:ledger ledger :token-key token-key :fetch fetch
                          :cache (atom {}) :now (constantly 1e12) :ids ids}
                         {:actor caller} "token_for"
                         {:actor/did target :credential/label "github-app"
                          :scope/repos ["example/widgets"] :scope/permissions requested}))]
    (with-redefs [app/app-jwt (fn [& _] "synthetic-app-jwt")]
      (doseq [[caller target requested] [[reader reader {"pull_requests" "write"}]
                                         [root reader {"contents" "write"}]
                                         [reader reader {}]
                                         [reader reader {"members" "read"}]
                                         [commenter commenter {"contents" "read"}]]]
        (let [out (await (mint caller target requested))]
          (is (= :forbidden (:error out)) (pr-str requested))
          (is (nil? (:result out)))))
      (is (empty? @calls) "no refused export reaches GitHub")
      (let [out (await (mint reader reader {"contents" "read"}))
            [_ _ body] (last @calls)]
        (is (= "synthetic-token" (get-in out [:result :token])))
        (is (= {"contents" "read"}
               (dissoc (get (js->clj (js/JSON.parse body)) "permissions") "metadata"))
            "explicitly narrowed permissions accompany the accepted mint")))))

(deftest exported-installation-tokens-require-safe-read-authority
  (async done
    (-> (exported-token-policy*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))
