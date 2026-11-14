(ns axxium.extern.drive-routes-test
  "Route-level failure injection; no Google account or network is used."
  (:require [axxium.auth.session :as session]
            [axxium.config :as cfg]
            [axxium.domain.drive :as drive]
            [axxium.domain.github-identity :as gi]
            [axxium.domain.policy :as policy]
            [axxium.domain.state :as state]
            [axxium.extern.github-identity-mcp :as agent-auth]
            [axxium.extern.google :as google]
            [axxium.extern.google-drive :as gd]
            [axxium.extern.http :as http]
            [axxium.extern.jose :as jose]
            [axxium.extern.ledger-file :as disk]
            [axxium.extern.ledger-queue :as queue]
            [axxium.extern.signing-key :as signing-key]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.google-drive :as law]
            [axxium.routes.drive :as routes]
            [axxium.routes.github-identity :as identity]
            [clio.shape.edn :as edn]
            [clojure.string :as str]
            [cljs.test :refer [async deftest is]]
            ["node:crypto" :as crypto]))

(defn- handlers []
  (let [registered (atom {})]
    (with-redefs [http/get! (fn [_ path handler] (swap! registered assoc path handler))
                  http/post! (fn [_ path handler] (swap! registered assoc path handler))]
      (routes/register-drive-routes! nil))
    @registered))

(defn- ^:async failed-persist* []
  (let [registered (handlers) response (atom nil) durable (atom [])
        fail-next (atom false) remote (atom {}) fetches (atom [])
        key (crypto/randomBytes 32)
        request #js {:body #js {:tool "drive_put" :arguments "{:drive/name \"x.edn\" :drive/value 42}"}}]
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id "alice"}))
                  cfg/get-in-config (fn [ks] (case ks
                                              [:axxium/public-base-url] "https://drive.test"
                                              [:jwt/issuer] "test"
                                              [:drive/ledger-file] "fixture-ledger"
                                              [:drive/token-key-file] "fixture-key"
                                              nil))
                  google/drive-client (fn [] {:client-id "c" :client-secret "s"})
                  tc/read-key-file (fn [_] key)
                  signing-key/current! (fn [] {})
                  jose/verify-google-state! (fn [& _] (js/Promise.resolve {:state "alice.state" :verifier "v"}))
                  http/query-param (fn [_ k] (if (= k "state") "alice.state" "code"))
                  http/cookie (fn [& _] "signed-state")
                  http/clear-state-cookie! (fn ([_ _] nil) ([_ _ _] nil))
                  http/redirect! (fn [_ target] (reset! response {:redirect target}))
                  http/send! (fn [_ status body] (reset! response {:status status :body body}))
                  http/send-secret! (fn [_ status body] (reset! response {:status status :body body}))
                  disk/load-ledger (fn [_] @durable)
                  disk/append-batch! (fn [_ before after]
                                       (is (= before @durable))
                                       (if (compare-and-set! fail-next true false)
                                         (throw (js/Error. "injected persistence failure"))
                                         (reset! durable after)))
                  gd/exchange-code (fn [fetch _]
                                     (swap! fetches conj fetch)
                                     (js/Promise.resolve {:scope law/drive-file-scope :refresh_token "fixture-refresh"}))
                  gd/refresh-access-token (fn [fetch _]
                                            (swap! fetches conj fetch)
                                            (js/Promise.resolve "at"))
                  gd/list-files (fn [& _] (js/Promise.resolve {:drive/files (vec (vals @remote))}))
                  gd/create-file (fn [_ _ args]
                                   (let [file {:drive/file-id "remote1" :drive/name (:drive/name args)}]
                                     (swap! remote assoc "remote1" file)
                                     (js/Promise.resolve file)))]
      (await ((get registered law/callback-path) nil nil))
      (is (= "/portal/index.html?drive=connected" (:redirect @response)))
      (reset! fail-next true)
      (await ((get registered "/api/drive/mcp") request nil))
      (is (= 500 (:status @response)) "remote success cannot become a successful response when persistence fails")
      (is (= 1 (count @remote)) "Google already created exactly one file")
      (await ((get registered "/api/drive/mcp") request nil))
      (is (= 400 (:status @response)))
      (let [out (edn/read-one (get-in @response [:body :edn]))]
        (is (= :exists-untracked (:error out)))
        (is (= ["remote1"] (:drive/existing out))))
      (is (= 1 (count @remote)) "retry does not duplicate or silently adopt the external file")
      (await ((get registered "/api/drive/mcp")
              #js {:body #js {:tool "drive_list" :arguments "{}"}} nil))
      (is (= 200 (:status @response)) "the rejected queued operation releases the next caller")
      (is (= [] (get-in (edn/read-one (get-in @response [:body :edn])) [:result :resources])))
      (is (seq @fetches))
      (is (every? #(not (identical? js/fetch %)) @fetches)
          "both OAuth exchange and Drive tools receive a timeout-capable fetch"))))

(deftest remote-create-then-persist-failure-can-be-retried-honestly
  (async done
    (-> (failed-persist*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))

(defn- fixture-tree [root actor sibling actor-key sibling-key]
  (let [policy {:policy/repos #{} :policy/permissions {} :policy/actions #{} :policy/forbid #{}}
        lg (:ledger (gi/register-root [] state/empty-state root "Owner" policy (agent-auth/event-ids)))
        provision (fn [lg did name]
                    (:ledger (gi/provision-actor lg (state/fold lg) root did name policy :agent
                               (agent-auth/event-ids))))
        lg (-> lg (provision actor "Codex") (provision sibling "Claude"))
        issue (fn [lg did key]
                (:ledger (gi/issue-bootstrap lg (state/fold lg) root did (agent-auth/digest key)
                           (agent-auth/event-ids))))]
    (-> lg (issue actor actor-key) (issue sibling sibling-key) state/fold)))

(defn- ^:async actor-drive* []
  (let [registered (handlers)
        root "did:web:drive-actor.test:actors:owner"
        actor (str root ":codex")
        managed (drive/actor-owner actor)
        sibling (str root ":claude")
        actor-key (str "axb_" (apply str (repeat 43 "a")))
        sibling-key (str "axb_" (apply str (repeat 43 "b")))
        tree (atom (fixture-tree root actor sibling actor-key sibling-key))
        inactive (assoc-in @tree [:resources (str "bot-actor:" actor) :resource/metadata :actor/status] :suspended)
        active @tree
        signing (assoc (await (signing-key/generate-ephemeral!)) :issuer "fixture")
        owner-id "browser-operator"
        browser-session "12345678-1234-1234-1234-123456789abc"
        other-session "12345678-1234-1234-1234-123456789abd"
        proof (atom {:actor-id owner-id :cred-id browser-session})
        durable (atom [])
        fail-file-append? (atom false)
        fail-reservation-fsync? (atom false)
        fail-recovery-fsync? (atom false)
        fsynced (atom [])
        writer (queue/create {:load! (fn [_] @durable)
                              :append! (fn [_ before after]
                                         (is (= before @durable))
                                         (when (and @fail-file-append?
                                                    (= law/file-kind (get-in (last after) [:event/body :resource/kind])))
                                           (reset! fail-file-append? false)
                                           (throw (js/Error. "injected file registration failure")))
                                         (reset! durable after)
                                         (when (and (= law/put-intent-kind (get-in (last after) [:event/body :resource/kind]))
                                                    (compare-and-set! fail-reservation-fsync? true false))
                                           (throw (js/Error. "reservation fsync failed after writing bytes")))
                                         (reset! fsynced after))})
        response (atom nil)
        state-cookie (atom nil)
        sealed-key (crypto/randomBytes 32)
        exchange-count (atom 0)
        fetch-count (atom 0)
        refresh-count (atom 0)
        lookup-count (atom 0)
        search-count (atom 0)
        generated (atom 0)
        uploads (atom 0)
        remote (atom {})
        interrupted-upload? (atom false)
        suspend-on-exchange (atom false)
        suspend-on-read (atom false)
        suspend-on-search (atom false)
        enabled? (atom true)
        reply! (fn [_ status body] (reset! response {:status status :body body}))
        origin "https://drive-actor.test"
        enrollment (fn [origin selected]
                     #js {:headers #js {:host "drive-actor.test" :origin origin}
                          :body #js {:actor-did selected}})
        callback (fn [state] #js {:query #js {:state state :code "test-code"}})
        agent (fn [did key tool args]
                #js {:headers #js {:host "drive-actor.test" :x-axxium-actor did
                                   :authorization (str "Bearer " key)}
                     :body #js {:tool tool :arguments (pr-str args)}})
        call! (fn ^:async invoke [path request]
                (await ((get registered path) request nil))
                @response)
        result (fn [] (edn/read-one (get-in @response [:body :edn])))]
    (let [signed (await (jose/sign-drive-actor-state!
                         {:state "fixture-state" :verifier "fixture-verifier"
                          :actor-did actor :root-did root :session-id browser-session}
                         signing))]
      (is (some? (await (jose/verify-drive-actor-state! signed signing)))))
    (with-redefs [session/resolve-auth-context (fn [_] (js/Promise.resolve {:auth/actor-id owner-id}))
                  session/human-session-proof! (fn [cookie]
                                                 (js/Promise.resolve (when (= "owner-session" cookie) @proof)))
                  cfg/get-in-config (fn [ks] (case ks
                                              [:axxium/public-base-url] origin
                                              [:jwt/issuer] "fixture"
                                              [:drive/actor-access?] @enabled?
                                              [:drive/ledger-file] "actor-drive-fixture"
                                              [:drive/token-key-file] "actor-drive-fixture-key"
                                              [:github-identity/ledger-file] "actor-drive-identity-fixture"
                                              [:identity/root-operator-binding] {:actor-id owner-id :root-did root}
                                              nil))
                  identity/current-actor-state #(deref tree)
                  identity/with-current-actor-state! (fn ^:async fixture-identity [f] {:result (await (f @tree))})
                  google/drive-client (fn [] {:client-id "fixture-client" :client-secret "fixture-secret"})
                  tc/read-key-file (fn [_] sealed-key)
                  signing-key/current! (fn [] signing)
                  http/cookie (fn [_ name]
                                (cond
                                  (= session/COOKIE-NAME name) "owner-session"
                                  (= "axxium_drive_actor_state" name) @state-cookie))
                  http/set-state-cookie! (fn
                                           ([_ _ _ _] (is false "Enrollment requires a purpose-specific cookie."))
                                           ([_ cookie token secure? path]
                                            (is (= "axxium_drive_actor_state" cookie))
                                            (is secure?)
                                            (is (= law/callback-path path))
                                            (reset! state-cookie token)))
                  http/clear-state-cookie! (fn
                                             ([_ _] (is false "Enrollment requires a purpose-specific cookie."))
                                             ([_ name path]
                                              (is (= law/callback-path path))
                                              (when (= "axxium_drive_actor_state" name) (reset! state-cookie nil))))
                  http/redirect! (fn [_ url] (reset! response {:redirect url}))
                  http/send! reply!
                  http/send-secret! reply!
                  queue/submit! (let [submit! queue/submit!]
                                  (fn
                                    ([_ path f] (submit! writer path f nil))
                                    ([_ path f append!]
                                     (is (or (nil? append!) (= disk/append-drive-batch! append!)))
                                     (submit! writer path f nil))))
                  disk/sync-drive-ledger! (fn [_ expected]
                                            (is (= expected @durable))
                                            (when (compare-and-set! fail-recovery-fsync? true false)
                                              (throw (js/Error. "recovery fsync failed")))
                                            (reset! fsynced expected))
                  gd/exchange-code (fn [_ _]
                                     (swap! exchange-count inc)
                                     (when @suspend-on-exchange (reset! tree inactive))
                                     (js/Promise.resolve {:scope law/drive-file-scope :refresh_token "actor-refresh"}))
                  gd/refresh-access-token (fn [_ request]
                                            (swap! refresh-count inc)
                                            (is (= "actor-refresh" (:refresh-token request)))
                                            (js/Promise.resolve "actor-access"))
                  gd/get-file (fn [_ _ file-id]
                                (swap! lookup-count inc)
                                (if (= "fixture-file" file-id)
                                  (do
                                    (swap! fetch-count inc)
                                    (when @suspend-on-read (reset! tree inactive))
                                    (js/Promise.resolve {:drive/content "{:fixture true}"}))
                                  (if-let [file (get @remote file-id)]
                                    (js/Promise.resolve file)
                                    (js/Promise.reject (ex-info "fixture file missing" {:status 404})))))
                  gd/generate-file-id (fn [_ _] (js/Promise.resolve (str "generated-" (swap! generated inc))))
                  gd/list-files (fn [_ _ {:keys [q]}]
                                  (swap! search-count inc)
                                  (when @suspend-on-search (reset! tree inactive))
                                  (js/Promise.resolve
                                    {:drive/files (vec (filter #(= q (law/same-name-query %)) (vals @remote)))}))
                  gd/create-file (fn [_ _ file]
                                   (swap! uploads inc)
                                   (is (some (fn [e] (and (= law/put-intent-kind (get-in e [:event/body :resource/kind]))
                                                        (= (:drive/file-id file) (get-in e [:event/body :resource/metadata :drive/file-id]))))
                                         @fsynced) "The upload must have a durable allowing reservation.")
                                   (is (policy/decision-before-effect? @durable))
                                   (is (nil? (:drive/folder-id file)))
                                   (swap! remote assoc (:drive/file-id file) file)
                                   (if (compare-and-set! interrupted-upload? true false)
                                     (js/Promise.reject (ex-info "upload response lost" {:status 503}))
                                     (js/Promise.resolve (dissoc file :drive/content))))]
      (is (= 403 (:status (await (call! "/api/drive/actor/connect" (enrollment "https://other.test" actor))))))
      (is (= 403 (:status (await (call! "/api/drive/actor/connect" (enrollment origin root))))))
      (reset! enabled? false)
      (is (= 503 (:status (await (call! "/api/drive/actor/connect" (enrollment origin actor))))))
      (reset! enabled? true)
      (swap! proof assoc :actor-id "unrelated-human")
      (is (= 401 (:status (await (call! "/api/drive/actor/connect" (enrollment origin actor))))))
      (swap! proof assoc :actor-id owner-id)
      (let [start (fn ^:async start-enrollment [target]
                    (let [out (await (call! "/api/drive/actor/connect" (enrollment origin target)))]
                      (when-not (= 200 (:status out))
                        (throw (ex-info "Expected actor enrollment to open consent" out)))
                      (let [url (js/URL. (get-in out [:body :authorization-url]))]
                        (is (not (str/includes? (.-href url) target)))
                        (is (= law/drive-file-scope (.get (.-searchParams url) "scope")))
                        (.get (.-searchParams url) "state"))))]
        (let [oauth-state (await (start actor))]
          (is (nil? (await (jose/verify-google-state! @state-cookie signing))))
          (swap! proof assoc :cred-id other-session)
          (is (= 400 (:status (await (call! law/callback-path (callback oauth-state))))))
          (is (zero? @exchange-count))
          (swap! proof assoc :cred-id browser-session))
        (let [oauth-state (await (start actor))]
          (reset! suspend-on-exchange true)
          (is (= 403 (:status (await (call! law/callback-path (callback oauth-state))))))
          (is (empty? @durable))
          (reset! tree active)
          (reset! suspend-on-exchange false))
        (let [oauth-state (await (start actor))]
          (is (= "/portal/index.html?drive=actor-connected"
                 (:redirect (await (call! law/callback-path (callback oauth-state))))))
          (is (= 400 (:status (await (call! law/callback-path (callback oauth-state))))))
          (is (= 2 @exchange-count)))
        (is (not (str/includes? (pr-str @durable) "actor-refresh")))
        (is (policy/decision-before-effect? @durable))
        (is (= root (:event/actor (last @durable))) "Enrollment is attributed to the authorizing human root.")
        (is (= "actor-refresh" (tc/open-sealed sealed-key (drive/credential-aad managed)
                                  (drive/sealed-credential (state/fold @durable) managed))))
        (is (nil? (drive/sealed-credential (state/fold @durable) (drive/actor-owner sibling))))
        (await (queue/submit! writer "actor-drive-fixture"
                 (fn [lg] (drive/connect lg (state/fold lg) actor (tc/seal sealed-key actor "human-refresh")
                            (agent-auth/event-ids)))))
        (await (queue/submit! writer "actor-drive-fixture"
                 (fn [lg] (drive/record-put lg (state/fold lg) actor
                            {:drive/file-id "human-file" :drive/name "human.edn"} [] (agent-auth/event-ids)))))
        (is (= "human-refresh" (tc/open-sealed sealed-key actor (drive/sealed-credential (state/fold @durable) actor))))
        (is (nil? (tc/open-sealed sealed-key actor (drive/sealed-credential (state/fold @durable) managed))))
        (let [id "google-drive:fixture-file"
              listing (agent actor actor-key "drive_list" {})
              reading (agent actor actor-key "drive_get" {:resource/id id})]
          (is (= 200 (:status (await (call! "/api/drive/agent" listing)))))
          (is (= [] (get-in (result) [:result :resources])) "Enrollment does not adopt existing Google files.")
          (is (= 400 (:status (await (call! "/api/drive/agent" (agent sibling sibling-key "drive_list" {}))))))
          (is (= :not-connected (:error (result))))
          (let [oauth-state (await (start sibling))]
            (is (= "/portal/index.html?drive=actor-connected"
                   (:redirect (await (call! law/callback-path (callback oauth-state)))))))
          (await (queue/submit! writer "actor-drive-fixture"
                   (fn [lg]
                     (drive/record-put lg (state/fold lg) managed
                       {:drive/file-id "fixture-file" :drive/name "fixture.edn"} [] (agent-auth/event-ids)))))
          (is (= 400 (:status (await (call! "/api/drive/agent"
                                      (agent actor actor-key "drive_get" {:resource/id "google-drive:human-file"}))))))
          (is (= :not-found (:error (result))))
          (is (= ["google-drive:human-file"] (mapv :resource/id (drive/owned-files (state/fold @durable) actor))))
          (is (nil? (drive/owned-file (state/fold @durable) actor id)))
          (is (= 401 (:status (await (call! "/api/drive/agent" (agent actor sibling-key "drive_list" {}))))))
          (is (= 400 (:status (await (call! "/api/drive/agent" (agent sibling sibling-key "drive_get" {:resource/id id}))))))
          (is (= :not-found (:error (result))))
          (is (= 400 (:status (await (call! "/api/drive/agent" (agent actor actor-key "drive_put" {}))))))
          (is (= :invalid (:error (result))))
          (is (zero? @fetch-count))
          (is (= 200 (:status (await (call! "/api/drive/agent" reading)))))
          (is (= {:fixture true} (get-in (result) [:result :drive/value])))
          (reset! suspend-on-read true)
          (is (= 401 (:status (await (call! "/api/drive/agent" reading)))))
          (is (nil? (get-in @response [:body :edn])))
          (reset! tree active))
        (let [payload {:drive/name "created.edn" :drive/idempotency-key "actor-create-1"
                       :drive/refs ["fixture-ref"] :drive/value {:value "actor-fixture-content"}}
              put! (fn ^:async put-with [args]
                     (await (call! "/api/drive/agent" (agent actor actor-key "drive_put" args))))
              file-id "google-drive:generated-1"]
          (is (= 400 (:status (await (put! (assoc payload :drive/folder-id "arbitrary-folder"))))))
          (is (= :invalid (:error (result))))
          (is (zero? @generated))
          (reset! fail-file-append? true)
          (is (= 400 (:status (await (put! payload)))))
          (is (= :reconciliation-required (:error (result))))
          (is (= 1 @uploads @generated))
          (is (nil? (drive/owned-file (state/fold @durable) managed file-id)))
          (is (not (str/includes? (pr-str @durable) "actor-fixture-content")))
          (is (not (str/includes? (pr-str @durable) "actor-create-1")))
          (is (= 400 (:status (await (put! (assoc payload :drive/value :changed))))))
          (is (= :idempotency-conflict (:error (result))))
          (is (= 200 (:status (await (put! payload)))))
          (is (= file-id (get-in (result) [:result :resource/id])))
          (is (= actor (get-in (result) [:result :resource/owner])))
          (is (= 200 (:status (await (put! payload)))))
          (is (= 1 @uploads @generated))
          (is (= 400 (:status (await (call! "/api/drive/agent" (agent sibling sibling-key "drive_get" {:resource/id file-id}))))))
          (is (= :not-found (:error (result))))
          (is (= 400 (:status (await (put! (assoc payload :drive/idempotency-key "a-new-key"))))))
          (is (= :exists (:error (result))))
          (let [ambiguous (assoc payload :drive/name "ambiguous.edn" :drive/idempotency-key "unknown-outcome")]
            (reset! interrupted-upload? true)
            (is (= 400 (:status (await (put! ambiguous)))))
            (is (= :reconciliation-required (:error (result))))
            (is (= 200 (:status (await (put! ambiguous)))))
            (is (= "google-drive:generated-3" (get-in (result) [:result :resource/id])))
            (is (= 2 @uploads) "A lost provider response does not cause a second upload."))
          (let [pending (assoc payload :drive/name "pending.edn" :drive/idempotency-key "previous-grant")]
            (reset! interrupted-upload? true)
            (is (= 400 (:status (await (put! pending)))))
            (is (= :reconciliation-required (:error (result))))
            (let [oauth-state (await (start actor))]
              (is (= "/portal/index.html?drive=actor-connected"
                     (:redirect (await (call! law/callback-path (callback oauth-state)))))))
            (let [before [@refresh-count @generated @uploads @lookup-count @search-count]]
              (is (= 200 (:status (await (put! payload)))))
              (is (= file-id (get-in (result) [:result :resource/id])))
              (is (= 400 (:status (await (put! pending)))))
              (is (= :reconciliation-required (:error (result))))
              (is (= before [@refresh-count @generated @uploads @lookup-count @search-count])
                  "Completed receipts replay locally after reconnection; old pending writes stay quarantined.")))
          (let [uncertain (assoc payload :drive/name "fsync.edn" :drive/idempotency-key "fsync-failure")]
            (reset! fail-reservation-fsync? true)
            (is (= 400 (:status (await (put! uncertain)))))
            (is (= :reconciliation-required (:error (result))))
            (is (not= @fsynced @durable) "The complete reservation is visible but its fsync failed.")
            (is (= 3 @uploads))
            (reset! fail-recovery-fsync? true)
            (is (= 400 (:status (await (put! uncertain)))))
            (is (= :reconciliation-required (:error (result))))
            (is (= 3 @uploads))
            (is (= 200 (:status (await (put! uncertain)))))
            (is (= "google-drive:generated-5" (get-in (result) [:result :resource/id])))
            (is (= 4 @uploads)))
          (reset! suspend-on-search true)
          (is (= 401 (:status (await (put! (assoc payload :drive/name "blocked.edn" :drive/idempotency-key "suspended"))))))
          (is (= 4 @uploads) "Suspension while searching stops the upload.")
          (is (policy/decision-before-effect? @durable)))))))

(deftest actor-drive-consent-and-own-resource-access
  (async done
    (-> (actor-drive*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))
