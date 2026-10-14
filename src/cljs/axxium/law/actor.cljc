(ns axxium.law.actor
  "Admission rules for human administration and agent credentials."
  (:require [malli.core :as m]))

(def AgentCreationRequest
  "Contract for POST /api/actors/agents after shape normalization."
  [:map [:display-name [:string {:min 1 :max 200}]]])

(def CredentialId
  "Contract for agent_credentials.id path values before they reach PostgreSQL uuid."
  [:re #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"])

(defn valid-agent-creation-request?
  "Admit a normalized agent-creation request only when it satisfies the contract."
  [request]
  (m/validate AgentCreationRequest request {:registry (m/default-schemas)}))

(defn valid-credential-id?
  "Admit a credential path id only when it is a UUID string the uuid column can bind.
   Length is checked explicitly: Malli :re uses re-find, and JS $ can match before a
   trailing newline, which PostgreSQL would then reject as a non-uuid."
  [id]
  (and (string? id)
       (= 36 (count id))
       (m/validate CredentialId id {:registry (m/default-schemas)})))

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
