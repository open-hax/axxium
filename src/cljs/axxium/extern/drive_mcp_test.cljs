(ns axxium.extern.drive-mcp-test
  "Drive MCP put/get/list against an in-memory Drive. No network."
  (:require [axxium.domain.drive :as drive]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.ledger :as ledger]
            [axxium.domain.policy :as policy]
            [axxium.domain.state :as state]
            [axxium.extern.drive-mcp :as mcp]
            [axxium.extern.google-drive :as gd]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.google-drive :as law]
            [axxium.shape.ledger-edn :as edn-lines]
            [clojure.string :as str]
            [cljs.test :refer [deftest is async testing]]
            ["node:crypto" :as crypto]))

(defn- respond [status body]
  #js {:ok (< status 400) :status status
       :json (fn [] (js/Promise.resolve (clj->js body)))
       :text (fn [] (js/Promise.resolve body))})

(deftest reserved-id-is-sent-with-the-multipart-create
  (async done
    (let [fetch (fn [url opts]
                  (is (= "Bearer fixture-access" (aget (.-headers opts) "authorization")))
                  (js/Promise.resolve
                    (if (str/includes? url "/generateIds?")
                      (do
                        (is (= (str gd/files-endpoint "/generateIds?count=1&space=drive&type=files") url))
                        (respond 200 {:kind "drive#generatedIds" :space "drive" :ids ["reserved-1"]}))
                      (let [multipart (.-body opts)
                            metadata-line (nth (str/split multipart #"\r\n") 3)]
                        (is (= "POST" (.-method opts)))
                        (is (str/starts-with? url (str gd/upload-endpoint "?uploadType=multipart")))
                        (is (= {:id "reserved-1" :name "fixture.edn" :mimeType law/edn-mime-type}
                               (js->clj (js/JSON.parse metadata-line) :keywordize-keys true)))
                        (respond 200 {:id "reserved-1" :name "fixture.edn" :mimeType law/edn-mime-type})))))]
      (-> (gd/generate-file-id fetch "fixture-access")
          (.then #(gd/create-file fetch "fixture-access"
                    {:drive/file-id % :drive/name "fixture.edn" :drive/mime-type law/edn-mime-type
                     :drive/content "{:fixture true}"}))
          (.then #(is (= "reserved-1" (:drive/file-id %))))
          (.catch (fn [e] (is false (pr-str e))))
          (.finally done)))))

(defn- fake-drive
  "A fetch over an atom {file-id {:name :parents :content}}. Grants an
   access token only for `refresh-token`; records every call."
  [files calls refresh-token]
  (fn [url opts]
    (let [method (or (some-> opts .-method) "GET")
          headers (some-> opts .-headers)
          authed? (= "Bearer at-1" (some-> headers (aget "authorization")))]
      (swap! calls conj [method url])
      (js/Promise.resolve
       (cond
         (str/includes? url "oauth2.googleapis.com/token")
         (if (str/includes? (.-body opts) (js/encodeURIComponent refresh-token))
           (respond 200 {:access_token "at-1"})
           (respond 400 {:error "invalid_grant"}))

         (not authed?) (respond 401 {})

         (str/includes? url "/upload/")
         (let [boundary (second (re-find #"boundary=(.*)" (aget headers "content-type")))
               [meta-part content-part] (str/split (.-body opts) (re-pattern (str "\r\n--" boundary)))
               meta (js->clj (js/JSON.parse (second (str/split meta-part #"\r\n\r\n" 2))) :keywordize-keys true)
               content (second (str/split content-part #"\r\n\r\n" 2))
               id (str "file" (inc (count @files)))]
           (swap! files assoc id {:name (:name meta) :parents (:parents meta)
                                  :mimeType (:mimeType meta) :content content})
           (respond 200 (assoc (select-keys (get @files id) [:name :parents :mimeType]) :id id)))

         (str/includes? url "q=")
         (let [q (js/decodeURIComponent (second (re-find #"q=([^&]*)" url)))
               wanted (second (re-find #"name = '((?:[^'\\]|\\.)*)'" q))
               parent (second (re-find #"'((?:[^'\\]|\\.)*)' in parents" q))]
           (respond 200 {:files (for [[id f] @files
                                     :let [parents (or (:parents f) ["root"])]
                                     :when (and (= wanted (:name f))
                                                (or (nil? parent) (some #{parent} parents)))]
                                  {:id id :name (:name f)})}))

         (str/includes? url "alt=media")
         (let [id (second (re-find #"/files/([^?]+)" url))]
           (if-let [f (get @files id)] (respond 200 (:content f)) (respond 404 "")))

         :else
         (let [id (second (re-find #"/files/([^?]+)" url))]
           (if-let [f (get @files id)]
             (respond 200 {:id id :name (:name f) :mimeType (:mimeType f) :parents (:parents f)})
             (respond 404 {}))))))))

(def embedding [0.1 -2.5e-8 1.5 3.141592653589793 1e300 5e-324 -0.000123456789012345 42])

(def value
  {:archive/kind :evidence-ledger
   :event/id "00000000-0000-4000-8000-0000000000aa"
   :embedding/model "nomic-embed-text"
   :embedding/vector embedding
   :text "héllo, \"quoted\" \\ and 'single' — ∑"
   :at #inst "2026-11-01T21:50:00.123-00:00"
   :uuid #uuid "11111111-2222-4333-8444-555555555555"
   :tags #{:a :b/c}
   :nested {"string-key" [1 2 {:deep nil}] 7 true}
   :ratio-free 0.30000000000000004})

(defn- ctx-ledger [key]
  (let [sealed (tc/seal key fx/alice "1//refresh-alice")]
    (:ledger (drive/connect ledger/empty-ledger (state/fold ledger/empty-ledger) fx/alice sealed
                            {:decision-id (fx/event-uuid 1) :effect-id (fx/event-uuid 2) :at fx/t0}))))

(defn- counter-ids []
  (let [n (atom 10)]
    (fn [] {:decision-id (fx/event-uuid (swap! n inc)) :effect-id (fx/event-uuid (swap! n inc)) :at fx/t1})))

(deftest credential-is-a-sealed-resource
  (let [key (crypto/randomBytes 32)
        lg (ctx-ledger key)
        r (get-in (state/fold lg) [:resources (law/credential-resource-id fx/alice)])]
    (is (= law/credential-kind (:resource/kind r)))
    (is (not (str/includes? (pr-str lg) "refresh-alice")) "no plaintext refresh token in the ledger")
    (is (= "1//refresh-alice" (tc/open-sealed key fx/alice (drive/sealed-credential (state/fold lg) fx/alice))))
    (is (policy/decision-before-effect? lg))))

(deftest lossless-edn-round-trip
  (async done
    (let [key (crypto/randomBytes 32)
          files (atom {})
          calls (atom [])
          deps {:fetch (fake-drive files calls "1//refresh-alice") :token-key key
                :client {:client-id "cid" :client-secret "csecret"} :ids (counter-ids)}
          lg0 (ctx-ledger key)
          ref "00000000-0000-4000-8000-0000000000aa"]
      (-> (mcp/call (assoc deps :ledger lg0) {:owner fx/alice} "drive_put"
                    {:drive/name "evidence.edn" :drive/folder-id "F" :drive/refs [ref] :drive/value value})
          (.then (fn [{:keys [ledger result error]}]
                   (is (nil? error))
                   (is (= "google-drive:file1" (:resource/id result)))
                   (is (= law/edn-mime-type (:mimeType (get @files "file1"))))
                   (is (policy/decision-before-effect? ledger))
                   (is (= ledger (edn-lines/import-lines (edn-lines/export-lines ledger)))
                       "the resource ledger itself round-trips as EDN lines")
                   (-> (mcp/call (assoc deps :ledger ledger) {:owner fx/alice} "drive_get"
                                 {:resource/id (:resource/id result)})
                       (.then (fn [got] [ledger got])))))
          (.then (fn [[ledger {:keys [result error]}]]
                   (is (nil? error))
                   (is (= value (:drive/value result)))
                   (testing "every embedding float is bit-identical"
                     (is (every? true? (map #(js/Object.is %1 %2) embedding
                                            (get-in result [:drive/value :embedding/vector])))))
                   (-> (js/Promise.all
                        #js [(mcp/call (assoc deps :ledger ledger) {:owner fx/alice} "drive_list" {:drive/ref ref})
                             (mcp/call (assoc deps :ledger ledger) {:owner fx/alice} "drive_list" {:drive/ref "other"})
                             (mcp/call (assoc deps :ledger ledger) {:owner fx/bob} "drive_get"
                                       {:resource/id "google-drive:file1"})
                             (mcp/call (assoc deps :ledger ledger) {:owner fx/alice} "drive_put"
                                       {:drive/name "evidence.edn" :drive/folder-id "F" :drive/value 1})])
                       (.then (fn [[by-ref none bob again]]
                                (is (= ["google-drive:file1"] (map :resource/id (get-in by-ref [:result :resources])))
                                    "a Mongo/clio event finds its Drive file by resource id")
                                (is (= [] (get-in none [:result :resources])))
                                (is (= :not-found (:error bob)) "another owner cannot read it")
                                (is (= :exists (:error again)) "put never overwrites")
                                (is (= 1 (count @files))))))))
          (.then (fn [_]
                   (is (every? #(contains? #{"GET" "POST"} (first %)) @calls) "no DELETE/PATCH ever")))
          (.catch (fn [e] (is false (pr-str e))))
          (.finally done)))))

(deftest refuses-without-connection-or-identity
  (async done
    (let [deps {:fetch (fake-drive (atom {}) (atom []) "x") :token-key (crypto/randomBytes 32)
                :client {:client-id "c" :client-secret "s"} :ids (counter-ids)
                :ledger ledger/empty-ledger}]
      (-> (js/Promise.all
           #js [(mcp/call deps {:owner nil} "drive_list" {})
                (mcp/call deps {:owner fx/alice} "drive_delete" {})
                (mcp/call deps {:owner fx/alice} "drive_put" {:drive/name "a" :drive/value 1})
                (mcp/call deps {:owner fx/alice} "drive_put" {:drive/name "" :drive/value 1})])
          (.then (fn [[anon unknown unconnected invalid]]
                   (is (= :unauthenticated (:error anon)))
                   (is (= :unknown-tool (:error unknown)))
                   (is (= :not-connected (:error unconnected)))
                   (is (= :invalid (:error invalid)))))
          (.catch (fn [e] (is false (pr-str e))))
          (.finally done)))))

(deftest actor-did-is-admitted
  (is (= "did:web:axxium.example:actors:actor_1" (law/actor-did "https://axxium.example" "actor_1")))
  (is (= "did:web:localhost%3A8787:actors:actor_1" (law/actor-did "http://localhost:8787/" "actor_1")))
  (is (= [] (drive/owned-files (state/fold ledger/empty-ledger) fx/alice))))

(defn- ^:async rejected-registration* []
  (let [key (crypto/randomBytes 32) files (atom {}) calls (atom [])
        lg (ctx-ledger key)
        deps {:fetch (fake-drive files calls "1//refresh-alice") :token-key key
              :client {:client-id "c" :client-secret "s"} :ids (counter-ids) :ledger lg}
        args {:drive/name "orphan.edn" :drive/value {:synthetic true}}
        first (with-redefs [drive/record-put (fn [ledger & _] {:ledger ledger})]
                (await (mcp/call deps {:owner fx/alice} "drive_put" args)))
        retried (await (mcp/call deps {:owner fx/alice} "drive_put" args))]
    (is (= :created-untracked (:error first)))
    (is (= :forbidden (:drive/reason first)))
    (is (= ["file1"] (:drive/existing first)))
    (is (= :exists-untracked (:error retried)))
    (is (= ["file1"] (:drive/existing retried)))
    (is (= 1 (count @files)))
    (is (= [] (drive/owned-files (state/fold lg) fx/alice)))))

(deftest rejected-registration-is-an-explicit-reconciliation-state
  (async done
    (-> (rejected-registration*)
        (.catch (fn [e] (is false (pr-str e))))
        (.finally done))))

(defn- ^:async duplicate-search-pages* []
  (let [key (crypto/randomBytes 32)
        args {:drive/name "evidence.edn" :drive/folder-id "F" :drive/value 1}
        match {:id "preexisting" :name "evidence.edn" :parents ["F"]}
        outcomes [[{:files [match]} :exists-untracked false]
                  [{:files []} nil true]
                  [{:kind "drive#fileList"} nil true]
                  [{:files [] :nextPageToken "page+2"} :drive-unavailable false]
                  [{:files [] :incompleteSearch true} :drive-unavailable false]
                  [{} :drive-unavailable false]
                  [{:kind "drive#fileList" :files {}} :drive-unavailable false]]]
    (doseq [[second-page expected-error upload?] outcomes]
      (let [calls (atom [])
            files (atom {})
            fetch (fake-drive files calls "1//refresh-alice")
            search (atom [])
            paged-fetch (fn [url opts]
                          (let [params (.-searchParams (js/URL. url))]
                            (if-let [q (.get params "q")]
                              (let [page-token (.get params "pageToken")]
                                (swap! search conj [q page-token (.-signal opts)])
                                (js/Promise.resolve
                                 (respond 200 (if page-token second-page
                                                  {:files [] :nextPageToken "page+2"}))))
                              (fetch url opts))))
            result (await (mcp/call {:fetch paged-fetch :token-key key
                                    :client {:client-id "cid" :client-secret "csecret"}
                                    :ids (counter-ids) :ledger (ctx-ledger key)}
                                   {:owner fx/alice} "drive_put" args))
            q "name = 'evidence.edn' and trashed = false and 'F' in parents"]
        (is (= [[q nil] [q "page+2"]] (mapv #(subvec % 0 2) @search)))
        (let [[first-signal second-signal] (map #(nth % 2) @search)]
          (is (instance? js/AbortSignal first-signal))
          (is (identical? first-signal second-signal) "both pages share the same search deadline"))
        (is (= expected-error (:error result)))
        (is (= (if upload? 1 0) (count (filter (fn [[method url]]
                                                 (and (= "POST" method) (str/includes? url "/upload/")))
                                               @calls))))
        (when (= :exists-untracked expected-error)
          (is (= ["preexisting"] (:drive/existing result))))))))

(deftest duplicate-search-must-finish-before-upload
  (async done
    (-> (duplicate-search-pages*)
        (.catch (fn [e] (is false (pr-str e))))
        (.finally done))))

(defn- ^:async put-searches-the-destination-folder* []
  (let [key (crypto/randomBytes 32)
        files (atom {"subfolder-file" {:name "evidence.edn" :parents ["F"]}})
        calls (atom [])
        deps {:fetch (fake-drive files calls "1//refresh-alice") :token-key key
              :client {:client-id "cid" :client-secret "csecret"}
              :ids (counter-ids) :ledger (ctx-ledger key)}
        args {:drive/name "evidence.edn" :drive/value 1}
        created (await (mcp/call deps {:owner fx/alice} "drive_put" args))
        root-retry (await (mcp/call (assoc deps :ledger (:ledger created))
                                    {:owner fx/alice} "drive_put" args))
        in-folder (await (mcp/call deps {:owner fx/alice} "drive_put"
                                   (assoc args :drive/folder-id "F")))]
    (is (nil? (:error created)) "the same name in a subfolder does not block a root put")
    (is (= "google-drive:file2" (get-in created [:result :resource/id])))
    (is (= :exists (:error root-retry)) "the resulting root duplicate is refused")
    (is (= :exists-untracked (:error in-folder)) "an explicit folder still checks its own duplicates")
    (is (= ["subfolder-file"] (:drive/existing in-folder)))
    (is (= 2 (count @files)))))

(deftest put-searches-the-destination-folder
  (async done
    (-> (put-searches-the-destination-folder*)
        (.catch (fn [e] (is false (pr-str e))))
        (.finally done))))

(defn- ^:async refresh-error-classification* []
  (let [key (crypto/randomBytes 32)
        deps {:token-key key :client {} :ids (counter-ids) :ledger (ctx-ledger key)}
        call-with (fn [body]
                    (mcp/call (assoc deps :fetch (fn [_ _] (js/Promise.resolve (respond 400 body))))
                              {:owner fx/alice} "drive_put" {:drive/name "a" :drive/value 1}))]
    (is (= :not-connected (:error (await (call-with {:error "invalid_grant"})))))
    (is (= :drive-unavailable (:error (await (call-with {:error "server_error"})))))))

(deftest refresh-errors-distinguish-reconnection-from-outage
  (async done
    (-> (refresh-error-classification*)
        (.catch (fn [e] (is false (pr-str e))))
        (.finally done))))
