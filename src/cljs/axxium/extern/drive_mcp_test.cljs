(ns axxium.extern.drive-mcp-test
  "Drive MCP put/get/list against an in-memory Drive. No network."
  (:require [axxium.domain.drive :as drive]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.ledger :as ledger]
            [axxium.domain.policy :as policy]
            [axxium.domain.state :as state]
            [axxium.extern.drive-mcp :as mcp]
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
               wanted (second (re-find #"name = '((?:[^'\\]|\\.)*)'" q))]
           (respond 200 {:files (for [[id f] @files :when (= wanted (:name f))]
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
  (is (drive/owned-files (state/fold ledger/empty-ledger) fx/alice) []))
