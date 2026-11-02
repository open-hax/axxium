(ns axxium.domain.kernel-test
  "Laws 1-7 and 10 of docs/math/access-kernel.md for actors, grants and policy."
  (:require [axxium.domain.actor :as actor]
            [axxium.domain.authority :as authority]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.ledger :as ledger]
            [axxium.domain.policy :as policy]
            [axxium.domain.state :as state]
            [axxium.law.did :as did]
            [axxium.law.event :as event]
            [axxium.shape.ledger-edn :as edn-lines]
            [cljs.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(defn- emit* [lg spec] (first (ledger/emit lg spec)))

(defn- register [lg n d kind]
  (emit* lg (actor/registration {:id (fx/event-uuid n) :at fx/t0 :registrar fx/service}
                                {:actor/did d :actor/kind kind :actor/name (name kind)})))

(defn- grant [lg n g]
  (emit* lg (authority/grant-event {:id (fx/event-uuid n) :at fx/t0 :granter fx/service} g)))

(def base
  (-> ledger/empty-ledger
      (register 1 fx/alice :human)
      (register 2 fx/bob :agent)
      (register 3 fx/team :team)
      (register 4 fx/org :org)
      (grant 5 (authority/membership-grant "g-team-alice" fx/alice fx/team))
      (grant 6 (authority/membership-grant "g-org-team" fx/team fx/org))
      (grant 7 {:grant/id "g-org-files" :grant/actor fx/org
                :grant/resource "file:/srv/shared" :grant/actions [:read]})))

(deftest dids
  (is (did/plc? fx/alice))
  (is (did/web? "did:web:axxium.example"))
  (is (did/web? "did:web:example.com:user:alice"))
  (is (not (did/did? "did:plc:short")))
  (is (not (did/did? "did:key:z6Mk"))))

(deftest actor-kinds
  (doseq [k actor/kinds]
    (is (actor/valid-actor? {:actor/did fx/carol :actor/kind k :actor/name "x"})))
  (is (not (actor/valid-actor? {:actor/did fx/carol :actor/kind :robot :actor/name "x"})))
  (is (= #{fx/alice fx/bob fx/team fx/org} (set (keys (:actors (state/fold base))))))
  (is (actor/collective? (state/fold base) fx/team)))

(deftest stream-order-and-identity
  (testing "law 1: contiguous seqs per stream"
    (is (= [1 2 3 4] (mapv :event/seq (ledger/stream base "actors"))))
    (is (= [1 2 3] (mapv :event/seq (ledger/stream base "grants")))))
  (testing "law 2: duplicate id and unknown cause are refused"
    (let [dup (ledger/event base {:id (fx/event-uuid 1) :stream "actors"
                                         :type :axxium.actor/registered
                                         :actor fx/service :at fx/t0
                                         :body {}})]
      (is (not (event/appendable? base dup))))
    (is (not (event/appendable? base (ledger/event base {:id (fx/event-uuid 99) :stream "x"
                                                         :type :a/b :actor fx/service
                                                         :at fx/t0 :body {}
                                                         :causes [(fx/event-uuid 98)]}))))))

(deftest membership-and-inheritance
  (let [s (state/fold base)]
    (testing "law 4"
      (is (= #{fx/alice} (authority/members s fx/team fx/t0))))
    (testing "law 5: alice reads through team -> org"
      (is (authority/allowed? s fx/alice "file:/srv/shared" :read fx/t0))
      (is (not (authority/allowed? s fx/bob "file:/srv/shared" :read fx/t0))))
    (testing "cycles terminate"
      (let [cyc (grant base 8 (authority/membership-grant "g-cycle" fx/org fx/team))]
        (is (authority/allowed? (state/fold cyc) fx/alice "file:/srv/shared" :read fx/t0))))))

(deftest revocation-is-monotone
  (let [revoked (emit* base (authority/revoke-event
                             {:id (fx/event-uuid 20) :at fx/t1 :revoker fx/service}
                             {:revoke/id "r1" :revoke/actor fx/alice
                              :revoke/resource (actor/actor-resource fx/team)
                              :revoke/at fx/t1}))
        s (state/fold revoked)]
    (is (not (authority/allowed? s fx/alice "file:/srv/shared" :read fx/t1)))
    (is (= #{} (authority/members s fx/team fx/t1)))))

(deftest expiry
  (let [lg (grant ledger/empty-ledger 1 {:grant/id "g" :grant/actor fx/bob
                                         :grant/resource "r" :grant/actions [:get]
                                         :grant/expires fx/t1})
        s (state/fold lg)]
    (is (authority/allowed? s fx/bob "r" :get fx/t0))
    (is (not (authority/allowed? s fx/bob "r" :get fx/t2)))))

(deftest decision-before-effect
  (let [s (state/fold base)
        effect {:stream "files" :type :axxium.file/read :actor fx/alice
                :body {:resource "file:/srv/shared"}}
        ok (policy/act base s {:actor fx/alice :resource "file:/srv/shared" :action :read :at fx/t0}
                       effect {:decision-id (fx/event-uuid 30) :effect-id (fx/event-uuid 31) :at fx/t0})
        no (policy/act base s {:actor fx/bob :resource "file:/srv/shared" :action :read :at fx/t0}
                       (assoc effect :actor fx/bob)
                       {:decision-id (fx/event-uuid 30) :effect-id (fx/event-uuid 31) :at fx/t0})]
    (is (= [(fx/event-uuid 30)] (get-in ok [:effect :event/causes])))
    (is (= [(fx/event-uuid 30) (fx/event-uuid 31)] (mapv :event/id (take-last 2 (:ledger ok)))))
    (is (policy/decision-before-effect? (:ledger ok)))
    (is (nil? (:effect no)))
    (is (= :no-grant (get-in no [:decision :event/body :decision/reason])))
    (is (= (inc (count base)) (count (:ledger no))))))

(deftest edn-round-trip
  (let [text (edn-lines/export-lines base)]
    (is (= base (edn-lines/import-lines text)))
    (is (= (edn-lines/canonical-ledger base)
           (edn-lines/canonical-ledger (edn-lines/import-lines text))))))

(defspec granted-actions-round-trip 50
  (prop/for-all [actions (gen/vector-distinct (gen/elements [:read :get :put :list :send]) {:min-elements 1})
                 n (gen/choose 1 999)]
    (let [lg (grant ledger/empty-ledger n {:grant/id (str "g" n) :grant/actor fx/carol
                                           :grant/resource "r" :grant/actions actions})]
      (and (= lg (edn-lines/import-lines (edn-lines/export-lines lg)))
           (every? #(authority/allowed? (state/fold lg) fx/carol "r" % fx/t0) actions)))))
