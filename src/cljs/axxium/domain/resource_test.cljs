(ns axxium.domain.resource-test
  "Registry, owner-issued grants, moves, and the decision-before-effect law."
  (:require [axxium.domain.authority :as authority]
            [axxium.domain.kernel-fixture :as fx]
            [axxium.domain.ledger :as ledger]
            [axxium.domain.policy :as policy]
            [axxium.domain.resource :as resource]
            [axxium.domain.state :as state]
            [axxium.shape.ledger-edn :as edn-lines]
            [cljs.test :refer [deftest is testing]]))

(def notes (resource/local-file fx/alice "/srv/notes.edn" {:file/size 10}))

(defn- ids [a b] {:decision-id (fx/event-uuid a) :effect-id (fx/event-uuid b) :at fx/t0})

(def registered
  (:ledger (resource/register ledger/empty-ledger (state/fold ledger/empty-ledger)
                              fx/alice notes (ids 1 2))))

(deftest local-kinds
  (is (resource/valid-resource? notes))
  (is (resource/valid-resource? (resource/local-process fx/service 42 "node dist/server.js")))
  (is (resource/valid-resource? (resource/local-program fx/service "/usr/bin/node")))
  (is (not (resource/valid-resource? (dissoc notes :resource/store :resource/location)))))

(deftest register-records-decision-first
  (is (= notes (get-in (state/fold registered) [:resources "file:/srv/notes.edn"])))
  (is (= [:axxium.policy/decided :axxium.resource/registered] (mapv :event/type registered)))
  (is (policy/decision-before-effect? registered))
  (testing "registering someone else's resource is refused"
    (let [{:keys [effect]} (resource/register ledger/empty-ledger (state/fold ledger/empty-ledger)
                                              fx/bob notes (ids 1 2))]
      (is (nil? effect)))))

(deftest owner-grants-and-revokes
  (let [s (state/fold registered)
        g {:grant/id "g1" :grant/actor fx/bob :grant/resource "file:/srv/notes.edn" :grant/actions [:read]}
        granted (:ledger (resource/issue-grant registered s fx/alice g (ids 3 4)))
        refused (resource/issue-grant registered s fx/bob g (ids 3 4))]
    (is (authority/allowed? (state/fold granted) fx/bob "file:/srv/notes.edn" :read fx/t0))
    (is (nil? (:effect refused)))
    (let [revoked (:ledger (resource/issue-revoke granted (state/fold granted) fx/alice
                                                  {:revoke/id "r1" :revoke/actor fx/bob
                                                   :revoke/resource "file:/srv/notes.edn" :revoke/at fx/t1}
                                                  (ids 5 6)))]
      (is (not (authority/allowed? (state/fold revoked) fx/bob "file:/srv/notes.edn" :read fx/t1)))
      (is (policy/decision-before-effect? revoked))
      (is (= revoked (edn-lines/import-lines (edn-lines/export-lines revoked)))))))

(deftest move-between-stores
  (let [moved (:ledger (resource/move registered (state/fold registered) fx/alice "file:/srv/notes.edn"
                                      :google-drive {:drive/file-id "abc"} (ids 7 8)))]
    (is (= :google-drive (get-in (state/fold moved) [:resources "file:/srv/notes.edn" :resource/store])))))
