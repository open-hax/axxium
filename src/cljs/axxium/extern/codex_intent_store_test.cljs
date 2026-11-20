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

(defn- store-snapshot [dir]
  (into {}
        (map (fn [filename]
               [filename (.toString (fs/readFileSync (path/join dir filename)) "base64")]))
        (js->clj (fs/readdirSync dir))))

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
        (is (= :invalid-store (failure-reason #(store/record-accepted! dir intent "unused-queue"))))
        (is (= :invalid-store
               (failure-reason #(store/checkpoint-terminal! dir (:caller-id intent) nil identity))))
        (is (= :invalid-store
               (failure-reason #(store/prepare-successor! dir (:caller-id intent) (:binding intent)))))))))

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

(def ^:private completed {:disposition :retire :turn-id "turn-complete" :item-id "item-matched"})
(def ^:private next-digest "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(deftest terminal-checkpoint-reserves-one-recoverable-successor
  (with-private-dir
    (fn [dir]
      (let [{:keys [caller-id binding]} intent
            verify (fn [saved] (is (= intent saved)) completed)]
        (is (= {:disposition :hold :reason :missing-intent}
               (store/checkpoint-terminal! dir caller-id next-digest verify)))
        (store/prepare! dir intent)
        (is (= {:disposition :hold :reason :not-attempted}
               (store/checkpoint-terminal! dir caller-id next-digest verify)))
        (is (= :not-retired
               (failure-reason #(store/prepare-successor! dir caller-id binding))))
        (is (true? (:permit? (store/begin-attempt! dir intent))))
        (let [{:keys [successor-intent] :as checkpoint}
              (store/checkpoint-terminal! dir caller-id next-digest verify)
              successor-id (:caller-id successor-intent)]
          (is (= :retired (:state checkpoint)))
          (is (= {:turn-id "turn-complete" :item-id "item-matched"} (:terminal checkpoint)))
          (is (= binding (:binding successor-intent)))
          (is (= next-digest (:notice-sha256 successor-intent)))
          (is (not= caller-id successor-id))
          (is (= checkpoint (store/recover! dir caller-id)))
          (is (= checkpoint (store/checkpoint-terminal! dir caller-id next-digest verify)))
          (is (nil? (store/recover! dir successor-id)))
          (is (= :reserved-caller-id
                 (failure-reason #(store/prepare! dir successor-intent))))
          (is (= :binding-mismatch
                 (failure-reason #(store/prepare-successor! dir caller-id
                                                            (assoc binding :generation "different")))))
          (is (= {:state :prepared :intent successor-intent}
                 (store/prepare-successor! dir caller-id binding)))
          (is (= :record-conflict
                 (failure-reason #(store/checkpoint-terminal! dir caller-id nil verify))))
          (is (= :record-conflict
                 (failure-reason #(store/checkpoint-terminal! dir caller-id next-digest
                                                               (constantly (assoc completed :turn-id "other"))))))
          (is (false? (:permit? (store/begin-attempt! dir intent))))
          (is (true? (:permit? (store/begin-attempt! dir successor-intent))))
          (is (= {:state :attempted :intent successor-intent}
                 (store/prepare-successor! dir caller-id binding)))
          (is (false? (:permit? (store/begin-attempt! dir successor-intent)))))))))

(deftest a-late-acceptance-preserves-the-original-terminal-witness
  (with-private-dir
    (fn [dir]
      (let [{:keys [caller-id binding]} intent
            check (constantly completed)]
        (store/prepare! dir intent)
        (store/begin-attempt! dir intent)
        (let [terminal (store/checkpoint-terminal! dir caller-id nil check)
              later (store/record-accepted! dir intent "late-queue-id")]
          (is (= :retired (:state later)))
          (is (= (:terminal terminal) (:terminal later)))
          (is (= "late-queue-id" (get-in later [:intent :queue-id])))
          (is (= later (store/checkpoint-terminal! dir caller-id nil check)))
          (is (nil? (store/prepare-successor! dir caller-id binding)))
          (is (false? (:permit? (store/begin-attempt! dir intent)))))))))

(deftest cannot-adopt-a-legacy-intent-as-a-new-successor
  (with-private-dir
    (fn [dir]
      (let [{:keys [caller-id binding]} intent
            check (constantly completed)]
        (store/prepare! dir intent)
        (store/begin-attempt! dir intent)
        (let [parent-file (record-with-suffix dir ".prepared")
              {:keys [successor-intent]} (store/checkpoint-terminal! dir caller-id next-digest check)
              terminal-file (record-with-suffix dir ".terminal")
              terminal-bytes (fs/readFileSync terminal-file)
              successor-id (:caller-id successor-intent)]
          (store/prepare-successor! dir caller-id binding)
          (store/begin-attempt! dir successor-intent)
          (store/record-accepted! dir successor-intent "legacy-queue-id")
          (let [child-file (->> (js->clj (fs/readdirSync dir))
                                (map #(path/join dir %))
                                (filter #(str/ends-with? % ".prepared"))
                                (remove #{parent-file})
                                first)
                decoded (js->clj (js/JSON.parse (fs/readFileSync child-file "utf8")))
                legacy (assoc decoded 0 1 5 nil)]
            (fs/writeFileSync child-file (str (js/JSON.stringify (clj->js legacy)) "\n"))
            (fs/unlinkSync terminal-file)
            (is (= :record-conflict
                   (failure-reason #(store/checkpoint-terminal! dir caller-id next-digest check))))
            (is (= :attempted (:state (store/recover! dir caller-id))))
            (is (= "legacy-queue-id" (get-in (store/recover! dir successor-id) [:intent :queue-id])))
            (is (= :accepted (:state (store/prepare! dir successor-intent))))
            (fs/writeFileSync terminal-file terminal-bytes #js {:mode 8r600})
            (is (= :record-conflict
                   (failure-reason #(store/prepare-successor! dir caller-id binding))))))))))

(deftest damaged-successor-refuses-before-publishing-any-record
  (with-private-dir
    (fn [fixture-dir]
      (let [{:keys [caller-id binding]} intent
            check (constantly completed)]
        (store/prepare! fixture-dir intent)
        (store/begin-attempt! fixture-dir intent)
        (let [{:keys [successor-intent]}
              (store/checkpoint-terminal! fixture-dir caller-id next-digest check)]
          (store/prepare-successor! fixture-dir caller-id binding)
          (store/begin-attempt! fixture-dir successor-intent)
          (store/record-accepted! fixture-dir successor-intent "fixture-successor-queue")
          (let [accepted-file (record-with-suffix fixture-dir ".accepted")
                accepted-name (path/basename accepted-file)]
            (testing "an orphaned successor acceptance leaves the parent attempted"
              (with-private-dir
                (fn [dir]
                  (store/prepare! dir intent)
                  (store/begin-attempt! dir intent)
                  (fs/copyFileSync accepted-file (path/join dir accepted-name))
                  (let [before (store-snapshot dir)]
                    (is (= :record-conflict
                           (failure-reason #(store/checkpoint-terminal! dir caller-id next-digest check))))
                    (is (= :attempted (:state (store/recover! dir caller-id))))
                    (is (false? (:permit? (store/begin-attempt! dir intent))))
                    (is (= :record-conflict
                           (failure-reason #(store/begin-attempt! dir successor-intent))))
                    (is (= before (store-snapshot dir)))))))
            (testing "a torn successor acceptance leaves the existing checkpoint intact"
              (with-private-dir
                (fn [dir]
                  (store/prepare! dir intent)
                  (store/begin-attempt! dir intent)
                  (let [checkpoint (store/checkpoint-terminal! dir caller-id next-digest check)]
                    (fs/writeFileSync (path/join dir accepted-name) "[torn" #js {:mode 8r600})
                    (let [before (store-snapshot dir)]
                      (is (= :invalid-record
                             (failure-reason #(store/prepare-successor! dir caller-id binding))))
                      (is (= checkpoint (store/recover! dir caller-id)))
                      (is (= :invalid-record
                             (failure-reason #(store/begin-attempt! dir successor-intent))))
                      (is (= before (store-snapshot dir))))))))))))))

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
