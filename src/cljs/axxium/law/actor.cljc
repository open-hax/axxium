(ns axxium.law.actor
  "Admission rules for human administration and agent credentials.")

(defn system-admin? [context entity]
  (and (= "human" (:kind entity))
       (some #{:axxium/system-admin "axxium/system-admin"}
             (:auth/roles context))))

(defn agent? [entity]
  (= "agent" (:kind entity)))

(defn valid-credential-request? [{:keys [label expires-in-hours]}]
  (and (string? label)
       (<= 1 (count label) 120)
       (integer? expires-in-hours)
       (<= 1 expires-in-hours 2160)))
