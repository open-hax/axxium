(ns axxium.extern.ledger-file-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.mailbox-mcp :as mm]
            [axxium.domain.mailbox-mcp-test :as mailbox-fixture]
            [axxium.extern.ledger-file :as lf]
            [axxium.extern.ledger-queue :as queue]
            [cljs.test :refer [async deftest is]]))

(deftest batch-persist-and-torn-tail
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-ledger-"))
        p (path/join dir "ledger.edn")
        r (mm/call [] {:principal {:auth/did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa" :auth/method :atproto}
                       :audience "héllo ∑" :now #inst "2026-11-01"} :open_session {:audience "héllo ∑" :ttl-ms 1000})
        lg (lf/append-batch! p [] (:ledger r))]
    (is (= lg (lf/load-ledger p)))
    (fs/appendFileSync p "{:event/id \"torn")
    (is (= lg (lf/load-ledger p)) "a torn trailing write is not admitted")
    (let [next (:ledger (mm/call lg {:principal {:auth/did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa" :auth/method :atproto}
                                   :audience "héllo ∑" :now #inst "2026-11-01"}
                               :open_session {:audience "héllo ∑" :ttl-ms 2000}))]
      (lf/append-batch! p lg next)
      (is (= next (lf/load-ledger p)) "append after recovery preserves every event and UTF-8 byte"))
    (fs/rmSync dir #js {:recursive true})))

(deftest entirely-torn-file-is-repaired-before-append
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-ledger-"))
        p (path/join dir "ledger.edn")]
    (try
      (fs/writeFileSync p "{:event/id \"torn ∑")
      (is (= [] (lf/load-ledger p)))
      (is (zero? (.-size (fs/statSync p))) "no complete line means no admitted bytes")
      (finally (fs/rmSync dir #js {:recursive true})))))

(deftest drive-sync-verifies-the-pre-provisioned-ledger
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-drive-sync-"))
        p (path/join dir "ledger.edn")
        link (path/join dir "link.edn")
        lg (:ledger (mm/call [] {:principal mailbox-fixture/alice
                                 :audience mailbox-fixture/aud :now fx/t0}
                              :open_session {:audience mailbox-fixture/aud :ttl-ms 3600000}))]
    (try
      (is (thrown? js/Error (lf/sync-drive-ledger! p [])))
      (fs/writeFileSync p "" #js {:mode 8r600})
      (lf/append-batch! p [] lg)
      (is (nil? (lf/sync-drive-ledger! p lg)))
      (is (thrown? js/Error (lf/sync-drive-ledger! p [])))
      (fs/symlinkSync p link)
      (is (thrown? js/Error (lf/sync-drive-ledger! link lg)))
      (fs/chmodSync p 8r644)
      (is (thrown? js/Error (lf/sync-drive-ledger! p lg)))
      (fs/chmodSync p 8r600)
      (fs/appendFileSync p "torn")
      (is (thrown? js/Error (lf/sync-drive-ledger! p lg)))
      (finally (fs/rmSync dir #js {:recursive true})))))

(deftest mailbox-batches-recover-as-complete-operations
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-mailbox-batch-"))
        p (path/join dir "ledger.edn")
        {:keys [ledger result]} (mm/call [] {:principal mailbox-fixture/alice
                                           :audience mailbox-fixture/aud :now fx/t0}
                                         :open_session {:audience mailbox-fixture/aud :ttl-ms 3600000})
        ctx (mailbox-fixture/ctx mailbox-fixture/alice (:session/id result))
        registered (:ledger (mm/call ledger ctx :register_mailbox {}))]
    (try
      (fs/writeFileSync p "" #js {:mode 8r600})
      (lf/append-batch! p [] ledger)
      (let [legacy (fs/readFileSync p)
            {:keys [load! append!]} (lf/private-ledger-storage p [])]
        (is (= ledger (load! p)) "existing event lines still load")
        (append! p ledger registered)
        (is (= registered (load! p)))
        (let [frame (.subarray (fs/readFileSync p) (.-length legacy))
              failed-cut
              (first
               (remove (fn [cut]
                         (fs/writeFileSync p (js/Buffer.concat #js [legacy (.subarray frame 0 cut)]))
                         (and (= ledger (load! p))
                              (.equals legacy (fs/readFileSync p))))
                       (range 1 (.-length frame))))]
          (is (nil? failed-cut) (str "a partial frame leaked events or bytes at cutoff " failed-cut))
          (fs/writeFileSync p legacy)
          (append! p ledger registered)
          (is (= registered (load! p)) "retry after truncation commits the complete operation"))
        (doseq [invalid ["[:axxium.mailbox/batch-v1]\n"
                         "[:axxium.mailbox/batch-v1 {:event/id :invalid}]\n"
                         "[:axxium.mailbox/batch-v2]\n"
                         "[:axxium.mailbox/batch-v1\n"]]
          (fs/writeFileSync p legacy)
          (fs/appendFileSync p (str invalid "{:uncommitted"))
          (let [before (fs/readFileSync p)]
            (is (thrown? js/Error (load! p)))
            (is (.equals before (fs/readFileSync p)) "a malformed complete frame leaves the file intact"))))
      (finally (fs/rmSync dir #js {:recursive true})))))

(deftest private-mailbox-refuses-capacity-overrun
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-mailbox-capacity-"))
        p (path/join dir "ledger.edn")
        limit (* 2 1024 1024)
        {:keys [ledger]} (mm/call [] {:principal mailbox-fixture/alice
                                    :audience "héllo" :now fx/t0}
                                  :open_session {:audience "héllo" :ttl-ms 3600000})]
    (try
      (fs/writeFileSync p "" #js {:mode 8r600})
      (let [{:keys [load! append!]} (lf/private-ledger-storage p [])]
        (append! p [] ledger)
        (let [frame (fs/readFileSync p)
              padding (js/Buffer.alloc (inc (- limit (.-length frame))) 0x20)
              final-index (dec (.-length padding))]
          ;; The audience's é needs two bytes, so counting code units would
          ;; incorrectly admit an append one byte above the cap.
          (.writeUInt8 padding 0x0a final-index)
          (fs/writeFileSync p padding)
          (is (thrown-with-msg? js/Error #"Mailbox ledger capacity exceeded"
                                (append! p [] ledger)))
          (is (.equals padding (fs/readFileSync p)))
          (.writeUInt8 padding 0x0a (dec final-index))
          (fs/writeFileSync p (.subarray padding 0 final-index))
          (append! p [] ledger)
          (is (= limit (.-size (fs/statSync p))))
          (is (= ledger (load! p)))
          (let [before (fs/readFileSync p)]
            (is (thrown-with-msg? js/Error #"Mailbox ledger capacity exceeded"
                                  (append! p [] ledger)))
            (is (.equals before (fs/readFileSync p))))
          (fs/appendFileSync p "x")
          (is (thrown-with-msg? js/Error #"Mailbox ledger capacity exceeded" (load! p)))
          (is (= (inc limit) (.-size (fs/statSync p)))
              "an oversized existing file is preserved for operator recovery")))
      (finally (fs/rmSync dir #js {:recursive true})))))

(deftest webhook-receipt-has-space-after-the-ordinary-append-ceiling
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-webhook-capacity-"))
        p (path/join dir "ledger.edn")
        ordinary-ceiling (- (* 2 1024 1024) (* 32 1024))
        {:keys [ledger]} (mm/call [] {:principal mailbox-fixture/alice
                                    :audience mailbox-fixture/aud :now fx/t0}
                                  :open_session {:audience mailbox-fixture/aud :ttl-ms 3600000})
        receipt [(assoc (first ledger) :event/type :axxium.outbox/delivered)]]
    (try
      (let [padding (js/Buffer.alloc ordinary-ceiling 0x20)]
        (.writeUInt8 padding 0x0a (dec ordinary-ceiling))
        (fs/writeFileSync p padding #js {:mode 8r600})
        (let [{:keys [load! append!]} (lf/private-ledger-storage p [] true)]
          (is (thrown-with-msg? js/Error #"Mailbox ledger capacity exceeded" (append! p [] ledger)))
          (is (.equals padding (fs/readFileSync p)))
          (is (= receipt (append! p [] receipt)))
          (is (= receipt (load! p)))
          (is (> (.-size (fs/statSync p)) ordinary-ceiling))))
      (finally (fs/rmSync dir #js {:recursive true})))))

(defn- send-mailbox
  [lg ctx to key body]
  (mm/call lg ctx :send_message {:to to :idempotency-key key :body body}))

(defn- ^:async mailbox-retry-and-reconnect* []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-mailbox-reconnect-"))
        p (path/join dir "ledger.edn")
        {:keys [lg a b]} (mailbox-fixture/world)
        sender (mailbox-fixture/ctx mailbox-fixture/alice a)
        recipient (mailbox-fixture/ctx mailbox-fixture/bob b)
        writer (queue/create)
        send! (fn [to body]
                (queue/submit! writer p #(send-mailbox % sender to "reconnect" body)))]
    (try
      (lf/append-batch! p [] lg)
      (let [[sent replay payload-conflict recipient-conflict]
            (array-seq (await (js/Promise.all #js [(send! fx/bob {:ordinal 1})
                                                   (send! fx/bob {:ordinal 1})
                                                   (send! fx/bob {:ordinal 2})
                                                   (send! fx/alice {:ordinal 1})])))
            message-id (get-in sent [:result :message/id])
            inbox (await (queue/submit! writer p #(mm/call % recipient :read_inbox {})))
            last-read (get-in inbox [:result :next-cursor])
            restarted (queue/create)
            opened (await (queue/submit! restarted p
                                         #(mm/call % (dissoc recipient :session/id) :open_session
                                                   {:audience mailbox-fixture/aud :ttl-ms 3600000})))
            resumed (assoc recipient :session/id (get-in opened [:result :session/id]))
            wrong-actor (await (queue/submit! restarted p
                                              #(mm/call % sender :acknowledge_message
                                                        {:message-id message-id})))
            recovered (await (queue/submit! restarted p
                                            #(mm/call % resumed :read_inbox {:after last-read})))]
        (is (seq (get-in sent [:result :message/receipt])))
        (is (= (:result sent) (:result replay)))
        (is (:replayed? replay))
        (is (= :idempotency-conflict (:error payload-conflict) (:error recipient-conflict)))
        (is (= 1 (count (mm/messages (lf/load-ledger p) fx/bob))))
        (is (pos? last-read) "read advanced its returned cursor without an acknowledgment")
        (is (not= b (:session/id resumed)))
        (is (= :not-found (:error wrong-actor)))
        (is (= [message-id] (mapv :message/id (get-in recovered [:result :messages])))))
      (finally (fs/rmSync dir #js {:recursive true})))))

(deftest mailbox-concurrent-retry-and-unread-reconnect
  (async done
    (-> (mailbox-retry-and-reconnect*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))

(deftest mailbox-dispatch-recovers-complete-acceptance-with-missing-outbox
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-mailbox-prefix-"))
        p (path/join dir "ledger.edn")
        {:keys [lg a]} (mailbox-fixture/world)
        sender (mailbox-fixture/ctx mailbox-fixture/alice a)
        body {:text "synthetic"}
        {accepted :result after :ledger} (send-mailbox lg sender fx/bob "prefix" body)
        message-id (:message/id accepted)]
    (try
      (is (= :axxium.outbox/pending (:event/type (peek after))))
      (lf/append-batch! p [] (pop after))
      (let [recovered (lf/load-ledger p)
            retry (send-mailbox recovered sender fx/bob "prefix" body)
            planned (mm/dispatch (:ledger retry) fx/t0)]
        (is (= :axxium.mailbox/accepted (:event/type (peek recovered))))
        (is (= accepted (:result retry)))
        (is (= 1 (count (mm/messages (:ledger retry) fx/bob))))
        (is (= [{:event "message.available" :recipient fx/bob :message/id message-id}]
               (:notifications planned)))
        (lf/append-batch! p recovered (:ledger planned))
        (is (empty? (:notifications (mm/dispatch (lf/load-ledger p) fx/t0)))
            "recovery never plans a persisted push twice"))
      (finally (fs/rmSync dir #js {:recursive true})))))
