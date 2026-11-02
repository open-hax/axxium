(ns axxium.domain.kernel-fixture
  "Deterministic ids, instants and DIDs for kernel tests.")

(defn event-uuid [n]
  (let [digits (str n)]
    (str "00000000-0000-4000-8000-"
         (apply str (repeat (- 12 (count digits)) "0")) digits)))

(def t0 #inst "2026-11-01T00:00:00.000-00:00")
(def t1 #inst "2026-11-02T00:00:00.000-00:00")
(def t2 #inst "2026-11-03T00:00:00.000-00:00")

(def service "did:web:axxium.example")
(def alice "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")
(def bob "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb")
(def carol "did:plc:cccccccccccccccccccccccc")
(def team "did:plc:tttttttttttttttttttttttt")
(def org "did:plc:oooooooooooooooooooooooo")
