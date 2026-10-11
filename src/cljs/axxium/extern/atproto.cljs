(ns axxium.extern.atproto
  "SSRF-protected AT Protocol DID and handle resolution via the official SDK."
  (:require ["@atproto/identity" :refer [IdResolver]]))

(defonce ^:private resolver (IdResolver. #js {:timeout 3000}))

(defn ^:async resolve-identity! [identity is-did]
  (let [did (if is-did identity (await (.resolve (.-handle resolver) identity)))
        data (when did (await (.resolveAtprotoData (.-did resolver) did)))
        handle (some-> data (.-handle))
        verified-did (when handle (await (.resolve (.-handle resolver) handle)))]
    (when (and data
               (= did verified-did)
               (or is-did (= (.toLowerCase identity) (.toLowerCase handle))))
      {:did (.-did data)
       :handle handle
       :pds (.-pds data)
       :signing-key (.-signingKey data)})))
