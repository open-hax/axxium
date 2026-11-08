(ns axxium.law.clio-pin-test
  "Pins the vendored clio canonical form of one fixed event. If the vendored
   copy drifts from open-hax/clio 9b14cda this expected string changes."
  (:require [clio.shape.canonical :as canonical]
            [cljs.test :refer [deftest is]]))

(def fixed-event
  {:event/id "00000000-0000-4000-8000-000000000001"
   :event/stream "mbox/did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"
   :event/seq 1
   :event/causes []
   :event/type :axxium.mbox/sent
   :event/actor "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb"
   :event/at #inst "2026-11-01T00:00:00.000-00:00"
   :event/body {:text "hi" :n 1.5 :tags #{:a :b}}})

(def expected-canonical
  "[:map [[[:keyword \"event\" \"actor\"] [:string \"did:plc:bbbbbbbbbbbbbbbbbbbbbbbb\"]] [[:keyword \"event\" \"at\"] [:inst [:number :safe-integer \"1793491200000\"]]] [[:keyword \"event\" \"body\"] [:map [[[:keyword nil \"n\"] [:number :real 1 \"6755399441055744\" -52]] [[:keyword nil \"tags\"] [:set [[:keyword nil \"a\"] [:keyword nil \"b\"]]]] [[:keyword nil \"text\"] [:string \"hi\"]]]]] [[:keyword \"event\" \"causes\"] [:sequential []]] [[:keyword \"event\" \"id\"] [:string \"00000000-0000-4000-8000-000000000001\"]] [[:keyword \"event\" \"seq\"] [:number :safe-integer \"1\"]] [[:keyword \"event\" \"stream\"] [:string \"mbox/did:plc:aaaaaaaaaaaaaaaaaaaaaaaa\"]] [[:keyword \"event\" \"type\"] [:keyword \"axxium.mbox\" \"sent\"]]]]")

(deftest canonical-form-is-pinned
  (is (= expected-canonical (canonical/canonical-edn fixed-event))))
