(ns axxium.law.principal-test
  "L5: one identity path. Only an actor node may act at a bound mailbox."
  (:require [axxium.law.principal :as principal]
            [cljs.test :refer [deftest is]]))

(def ^:private node "did:web:axxium.example:actors:owner:grokbot")
(def ^:private plc "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")

(deftest an-actor-node-is-a-did-web-acting-as-itself
  (is (= {:auth/did node :auth/method :actor-node} (principal/from-actor-node node)))
  (is (= {:auth/did node :auth/method :actor-node} (principal/from-actor-node node node))
      "its own DID is not a separate mailbox")
  (is (= plc (principal/address (principal/from-actor-node node plc))))
  (is (= node (principal/address (principal/from-actor-node node))))
  (is (nil? (principal/from-actor-node plc)) "an external account is never a node"))

(deftest external-accounts-act-only-at-their-own-did
  (is (= plc (principal/address (principal/from-atproto-session plc))))
  (is (not (principal/authenticated? {:auth/did plc :auth/method :atproto :auth/mailbox node})))
  (is (not (principal/authenticated? {:auth/did plc :auth/method :owner-delegation}))
      "the borrowed owner-delegation method is gone"))
