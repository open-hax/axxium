(ns axxium.extern.bcrypt
  "Thin extern wrapper around the `bcrypt` npm module."
  (:refer-clojure :exclude [hash compare])
  (:require ["bcryptjs" :as bcrypt-lib]))

(defn hash
  "Hash a password with given salt rounds. Returns Promise<hash>."
  [password salt-rounds]
  (.hash bcrypt-lib password salt-rounds))

(defn compare
  "Compare a password against a hash. Returns Promise<boolean>."
  [password hash]
  (.compare bcrypt-lib password hash))
