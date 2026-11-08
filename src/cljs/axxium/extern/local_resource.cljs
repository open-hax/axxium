(ns axxium.extern.local-resource
  "Reads host facts for local file resources. Returns plain maps; never reads
   file contents."
  (:require ["node:fs" :as fs]))

(defn file-metadata
  "Size and mtime for `path`, or nil when it is not a regular file."
  [path]
  (try
    (let [st (.statSync fs path)]
      (when (.isFile st)
        {:file/size (.-size st)
         :file/modified (js/Date. (.-mtimeMs st))}))
    (catch :default _ nil)))
