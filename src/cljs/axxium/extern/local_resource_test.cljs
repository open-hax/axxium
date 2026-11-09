(ns axxium.extern.local-resource-test
  "Host fact reads for local file resources."
  (:require [axxium.extern.local-resource :as local]
            [cljs.test :refer [deftest is]]))

(deftest file-metadata
  (is (nil? (local/file-metadata "/definitely/not/here")))
  (is (pos? (:file/size (local/file-metadata "package.json")))))
