(ns axxium.extern.json-test
  (:require [axxium.extern.json :as json]
            [cljs.test :refer [deftest is]]))

(deftest actor-authority-retains-qualified-keywords
  (is (= ["axxium/system-admin" "axxium/login"]
         (js->clj (js/JSON.parse (json/encode [:axxium/system-admin :axxium/login]))))))
