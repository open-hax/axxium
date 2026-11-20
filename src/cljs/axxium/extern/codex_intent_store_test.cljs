(ns axxium.extern.codex-intent-store-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [axxium.extern.codex-intent-store :as store]
            [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]))

(def ^:private intent {:binding {:actor-did "did:web:example.test:actors:fixture"
                                :device-id "fixture-device"
                                :thread-id "fixture-thread"
                                :generation "fixture-generation"}
                       :caller-id "attempt-1"
                       :notice-sha256 "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})

(defn- linux? [] (= "linux" (.-platform js/process)))

(defn- with-private-dir [run]
  (when (linux?)
    (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-codex-intent-"))]
      (try
        (run (fs/realpathSync dir))
        (finally (fs/rmSync dir #js {:recursive true :force true}))))))

(defn- record-with-suffix [dir suffix]
  (->> (js->clj (fs/readdirSync dir))
       (filter #(str/ends-with? % suffix))
       first
       (path/join dir)))

(defn- failure-reason [run]
  (try
    (run)
    nil
    (catch :default failure (:reason (ex-data failure)))))

(deftest refuse-unsupported-or-relative-stores
  (if (linux?)
    (is (= :invalid-store (failure-reason #(store/prepare! "./relative" intent))))
    (testing "platforms without Linux descriptor paths fail closed"
      (let [dir (path/join (os/tmpdir) "unused-axxium-intent-store")]
        (is (= :invalid-store (failure-reason #(store/prepare! dir intent))))
        (is (= :invalid-store (failure-reason #(store/recover! dir (:caller-id intent)))))
        (is (= :invalid-store (failure-reason #(store/begin-attempt! dir intent))))
        (is (= :invalid-store (failure-reason #(store/record-accepted! dir intent "unused-queue"))))))))

(deftest immutable-dispatch-custody
  (with-private-dir
    (fn [dir]
      (is (nil? (store/recover! dir (:caller-id intent))))
      (is (= :intent-mismatch (failure-reason #(store/begin-attempt! dir intent))))
      (let [prepared {:state :prepared :intent intent}
            attempted {:state :attempted :intent intent}
            accepted {:state :accepted :intent (assoc intent :queue-id "native-queue-1")}]
        (is (= prepared (store/prepare! dir intent)))
        (is (= prepared (store/prepare! dir intent)))
        (is (= :not-attempted
               (failure-reason #(store/record-accepted! dir intent "native-queue-1"))))
        (is (= (assoc attempted :permit? true) (store/begin-attempt! dir intent)))
        (is (= attempted (store/recover! dir (:caller-id intent))))
        (is (= attempted (store/prepare! dir intent)))
        (is (= (assoc attempted :permit? false) (store/begin-attempt! dir intent)))
        (is (= accepted (store/record-accepted! dir intent "native-queue-1")))
        (is (= accepted (store/record-accepted! dir intent "native-queue-1")))
        (is (= :record-conflict
               (failure-reason #(store/record-accepted! dir intent "different-queue"))))
        (is (= accepted (store/recover! dir (:caller-id intent))))
        (is (= (assoc accepted :permit? false) (store/begin-attempt! dir intent)))
        (doseq [suffix [".prepared" ".attempted" ".accepted"]]
          (is (= 8r600 (bit-and 8r777 (.-mode (fs/lstatSync (record-with-suffix dir suffix)))))))))))

(deftest original-proof-constrains-retries
  (with-private-dir
    (fn [dir]
      (let [exact (assoc intent :caller-id "../reserved/input")
            other-device (assoc-in exact [:binding :device-id] "different-device")
            wrong-digest (assoc exact :notice-sha256
                                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")]
        (is (= {:state :prepared :intent exact} (store/prepare! dir exact)))
        (is (= :intent-mismatch (failure-reason #(store/prepare! dir other-device))))
        (is (= :intent-mismatch (failure-reason #(store/begin-attempt! dir wrong-digest))))
        (is (= :invalid-intent (failure-reason #(store/prepare! dir (assoc exact :proof-token "invalid")))))
        (is (true? (:permit? (store/begin-attempt! dir exact))))
        (is (= 2 (.-length (fs/readdirSync dir))))))))

(deftest oversized-accepted-frame-leaves-the-attempt-recoverable
  (with-private-dir
    (fn [dir]
      (let [escaped-id (apply str (repeat 512 (char 0)))
            exact (assoc intent :caller-id escaped-id
                         :binding (zipmap (keys (:binding intent)) (repeat escaped-id)))]
        (store/prepare! dir exact)
        (is (true? (:permit? (store/begin-attempt! dir exact))))
        (is (= :record-too-large
               (failure-reason #(store/record-accepted! dir exact escaped-id))))
        (is (= {:state :attempted :intent exact} (store/recover! dir escaped-id)))
        (is (false? (:permit? (store/begin-attempt! dir exact))))
        (is (= 2 (.-length (fs/readdirSync dir))))))))

(deftest malformed-or-unpublished-state-never-creates-a-duplicate-permit
  (with-private-dir
    (fn [dir]
      (testing "an abandoned staging file is outside the committed record set"
        (fs/writeFileSync (path/join dir ".intent-abandoned.tmp") "[torn" #js {:mode 8r600})
        (is (= {:state :prepared :intent intent} (store/prepare! dir intent)))
        (is (true? (:permit? (store/begin-attempt! dir intent)))))
      (testing "an attempted record with a missing prepared record fails closed"
        (let [prepared (record-with-suffix dir ".prepared")
              displaced (path/join dir ".fixture-displaced")]
          (fs/renameSync prepared displaced)
          (is (= :record-conflict (failure-reason #(store/recover! dir (:caller-id intent)))))
          (is (= :record-conflict (failure-reason #(store/prepare! dir intent))))
          (is (= :record-conflict (failure-reason #(store/begin-attempt! dir intent))))
          (fs/renameSync displaced prepared)))
      (testing "a torn committed record is held"
        (let [attempted (record-with-suffix dir ".attempted")]
          (fs/writeFileSync attempted "[1,\"attempted\",")
          (is (= :invalid-record (failure-reason #(store/recover! dir (:caller-id intent)))))
          (is (= :invalid-record (failure-reason #(store/begin-attempt! dir intent))))
          (is (= "[1,\"attempted\"," (fs/readFileSync attempted "utf8"))))))))

(deftest reject-unsafe-filesystem-entries
  (with-private-dir
    (fn [dir]
      (testing "the provisioned store must be a private real directory"
        (fs/chmodSync dir 8r755)
        (is (= :invalid-store (failure-reason #(store/prepare! dir intent))))
        (fs/chmodSync dir 8r700)
        (let [alias (path/join dir "alias")]
          (fs/symlinkSync dir alias)
          (is (= :invalid-store (failure-reason #(store/prepare! alias intent))))))
      (testing "public and symbolic committed records are refused"
        (store/prepare! dir intent)
        (let [prepared (record-with-suffix dir ".prepared")
              copy (path/join dir "prepared-copy")]
          (fs/chmodSync prepared 8r644)
          (is (= :invalid-record (failure-reason #(store/begin-attempt! dir intent))))
          (fs/chmodSync prepared 8r600)
          (fs/renameSync prepared copy)
          (fs/symlinkSync copy prepared)
          (is (thrown? js/Error (store/begin-attempt! dir intent)))
          (is (= 3 (.-length (fs/readdirSync dir)))))))))
