(ns axxium.extern.google-drive-test
  (:require [axxium.extern.google-drive :as gd]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.google-drive :as law]
            [cljs.test :refer [deftest is async]]
            ["node:crypto" :as crypto]))

(deftest authorize-params
  (let [p (law/authorization-params {:client-id "cid" :redirect-uri "https://x/api/drive/callback"
                                     :state "s" :challenge "c"})]
    (is (= law/drive-file-scope (get p "scope")))
    (is (= "offline" (get p "access_type")))
    (is (= "consent" (get p "prompt"))))
  (is (law/granted-scopes-ok? law/drive-file-scope))
  (is (not (law/granted-scopes-ok? (str law/drive-file-scope " https://www.googleapis.com/auth/drive")))))

(deftest put-never-overwrites
  (is (= :refused (:drive/outcome (law/put-plan [{:drive/file-id "f1"}] {}))))
  (is (= :create (:drive/outcome (law/put-plan [] {}))))
  (is (= "name = 'a\\'b' and trashed = false and 'F' in parents"
         (law/same-name-query {:drive/name "a'b" :drive/folder-id "F"}))))

(deftest no-delete-surface
  (is (empty? (filter #(re-find #"(?i)delete|trash|update|patch" (str %))
                      (keys (ns-publics 'axxium.extern.google-drive))))))

(deftest refresh-token-sealing
  (let [k (crypto/randomBytes 32)
        sealed (tc/seal k "did:plc:a" "1//refresh")]
    (is (not (re-find #"refresh" sealed)))
    (is (= "1//refresh" (tc/open-sealed k "did:plc:a" sealed)))
    (is (nil? (tc/open-sealed k "did:plc:b" sealed)) "bound to the actor")
    (is (nil? (tc/open-sealed (crypto/randomBytes 32) "did:plc:a" sealed)))
    (is (nil? (tc/key-from-base64 "c2hvcnQ=")))))

(defn- fake-fetch [calls]
  (fn [url opts]
    (swap! calls conj [url (some-> opts .-method)])
    (js/Promise.resolve
     #js {:ok true :status 200
          :json (fn [] (js/Promise.resolve
                        (clj->js (cond (re-find #"upload" url) {:id "new" :name "n.txt" :parents ["F"]}
                                       (re-find #"q=" url) {:files []}
                                       :else {:id "new" :name "n.txt"}))))
          :text (fn [] (js/Promise.resolve "hello"))})))

(deftest fake-roundtrip
  (async done
    (let [calls (atom [])
          f (fake-fetch calls)]
      (-> (gd/list-files f "at" {:q (law/same-name-query {:drive/name "n.txt"})})
          (.then (fn [r] (is (= [] (:drive/files r)))
                   (gd/create-file f "at" {:drive/name "n.txt" :drive/folder-id "F" :drive/content "hello"})))
          (.then (fn [r] (is (= "new" (:drive/file-id r))) (gd/get-file f "at" "new")))
          (.then (fn [r] (is (= "hello" (:drive/content r)))
                   (is (every? #(contains? #{nil "POST"} (second %)) @calls))))
          (.catch (fn [e] (is false (pr-str e))))
          (.finally done)))))

(defn- ^:async timeout-options* []
  (let [controller (js/AbortController.)
        headers #js {:authorization "Bearer fixture"}
        opts #js {:method "POST" :body "fixture" :headers headers :signal (.-signal controller)}
        seen (atom nil)
        fetch (fn [url options] (reset! seen [url options]) (js/Promise.resolve :ok))
        bounded (gd/with-timeout fetch 5)]
    (is (= :ok (await (bounded "https://fixture.invalid" opts))))
    (let [[url actual] @seen]
      (is (= "https://fixture.invalid" url))
      (is (= "POST" (.-method actual)))
      (is (= "fixture" (.-body actual)))
      (is (identical? headers (.-headers actual)))
      (is (identical? (.-signal controller) (.-signal opts)) "input options are not mutated")
      (is (not (identical? (.-signal controller) (.-signal actual))) "caller signal cannot override the timeout")
      (await (js/Promise. (fn [resolve _] (js/setTimeout resolve 20))))
      (is (true? (.-aborted (.-signal actual))) "the timeout aborts even while the caller remains live")
      (is (false? (.-aborted (.-signal controller))))))
  (let [controller (js/AbortController.)
        seen (atom nil)
        bounded (gd/with-timeout (fn [_ opts] (reset! seen (.-signal opts))) 10000)]
    (bounded "https://fixture.invalid" #js {:signal (.-signal controller)})
    (.abort controller)
    (is (true? (.-aborted @seen)) "earlier caller cancellation also propagates")))

(deftest timeout-preserves-options-and-caller-cancellation
  (async done
    (-> (timeout-options*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))

(defn- ^:async provider-error-details* []
  (doseq [[body expected] [[(fn [] (js/Promise.reject (js/Error. "invalid JSON"))) {:status 503}]
                           [(fn [] (js/Promise.resolve #js {:error "invalid_grant"}))
                            {:status 400 :oauth-error "invalid_grant"}]]]
    (let [fetch (fn [_ _] (js/Promise.resolve #js {:ok false :status (:status expected) :json body}))
          data (try (await (gd/refresh-access-token fetch {})) nil
                    (catch :default e (ex-data e)))]
      (is (= expected data)))))

(deftest provider-errors-preserve-status
  (async done
    (-> (provider-error-details*)
        (.catch (fn [e] (is false (pr-str e))))
        (.finally done))))
