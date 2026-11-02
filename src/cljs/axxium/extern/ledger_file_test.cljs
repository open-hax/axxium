(ns axxium.extern.ledger-file-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [axxium.domain.mailbox-mcp :as mm]
            [axxium.extern.ledger-file :as lf]
            [cljs.test :refer [deftest is]]))

(deftest batch-persist-and-torn-tail
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "axxium-ledger-"))
        p (path/join dir "ledger.edn")
        r (mm/call [] {:principal {:auth/did "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa" :auth/method :atproto}
                       :audience "a" :now #inst "2026-11-01"} :open_session {:audience "a" :ttl-ms 1000})
        lg (lf/append-batch! p [] (:ledger r))]
    (is (= lg (lf/load-ledger p)))
    (fs/appendFileSync p "{:event/id \"torn")
    (is (= lg (lf/load-ledger p)) "a torn trailing write is not admitted")
    (fs/rmSync dir #js {:recursive true})))
