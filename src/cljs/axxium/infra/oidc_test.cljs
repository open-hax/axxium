(ns axxium.infra.oidc-test
  "Verify the production authority composition against the actual query contract."
  (:require [axxium.config :as config]
            [axxium.db :as db]
            [axxium.extern.env :as environment]
            [axxium.extern.oidc-http :as transport]
            [axxium.infra.oidc :as oidc]
            [cljs.test :refer [deftest is]]))

(deftest installed-authority-looks-up-the-exact-subject
  (let [query (atom nil)
        actor {:id "actor_fixture" :status "active"}]
    (with-redefs [db/pool (delay :pool)
                  environment/get-value (constantly nil)
                  config/get-in-config (constantly nil)
                  db/query-one-sql (fn [statement] (reset! query statement) actor)
                  transport/install! (fn [_ pool _options _verify lookup]
                                       (is (= :pool pool))
                                       (is (= actor (lookup "actor_fixture"))))]
      (oidc/enable! :app))
    (is (= (db/q-select-actor-by-id {:id "actor_fixture"}) @query))
    (is (= [:= :id "actor_fixture"] (:where @query)))))
