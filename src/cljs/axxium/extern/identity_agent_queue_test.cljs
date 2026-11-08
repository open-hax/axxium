(ns axxium.extern.identity-agent-queue-test
  (:require [axxium.config :as cfg]
            [axxium.domain.state :as state]
            [axxium.extern.github-identity-mcp :as mcp]
            [axxium.extern.ledger-file :as disk]
            [axxium.extern.ledger-queue :as queue]
            [axxium.extern.token-crypto :as tc]
            [axxium.routes.github-identity :as route]
            [cljs.test :refer [async deftest is]]))

(defn- ^:async rotation-before-agent-request* []
  (let [writer (queue/create)
        durable (atom [:old-key])
        called (atom 0)]
    (with-redefs [disk/load-ledger (fn [_] @durable)
                  disk/append-batch! (fn [_ _ after] (reset! durable after))
                  state/fold (fn ([ledger] ledger) ([_ ledger] ledger))
                  cfg/get-in-config (fn [_] nil)
                  tc/read-key-file (fn [_] nil)
                  mcp/authenticate (fn [st did key]
                                     (when (= key (last st)) did))
                  mcp/call (fn [_ _ _ _] (swap! called inc) {:result :ok})]
      (let [rotation (queue/submit! writer "identity" (fn [_] {:ledger [:new-key]}))
            old-request (queue/submit! writer "identity"
                                       #(route/agent-operation % "agent" :old-key "actor_list" {}))]
        (await rotation)
        (is (= 401 (:agent/status (await old-request))))
        (is (zero? @called) "revoked key never reaches the tool"))
      (is (= 403 (:agent/status
                  (await (queue/submit! writer "identity"
                                        #(route/agent-operation % "agent" :new-key "bootstrap_issue" {}))))))
      (is (= 400 (:agent/status
                  (await (queue/submit! writer "identity"
                                        #(route/agent-operation % "agent" :new-key "actor_list" ::route/bad))))))
      (is (= 200 (:agent/status
                  (await (queue/submit! writer "identity"
                                        #(route/agent-operation % "agent" :new-key "actor_list" {}))))))
      (is (= 1 @called)))))

(deftest rotation-precedes-authentication
  (async done
    (-> (rotation-before-agent-request*)
        (.catch (fn [error] (is false (pr-str error))))
        (.finally done))))
