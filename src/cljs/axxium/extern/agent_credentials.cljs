(ns axxium.extern.agent-credentials
  "Random bearer secrets and their database-safe digests."
  (:require ["node:crypto" :as crypto]))

(defn issue-token []
  (str "axx_" (.toString (.randomBytes crypto 32) "base64url")))

(defn token-hash [token]
  (-> (.createHash crypto "sha256")
      (.update token)
      (.digest "hex")))

(defn expires-at [hours]
  (js/Date. (+ (.getTime (js/Date.)) (* hours 3600000))))

(defn now-ms []
  (.getTime (js/Date.)))
