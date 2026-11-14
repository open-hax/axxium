(ns axxium.domain.github-comment
  "Authority and durable decisions for one managed GitHub comment."
  (:require [axxium.domain.github-identity :as identity]
            [axxium.domain.ledger :as ledger]
            [axxium.domain.policy :as policy]
            [axxium.law.event :as event]
            [axxium.law.github-identity :as law]
            [clojure.string :as str]))

(def comment-permissions {"issues" "write"})

(defn valid-request?
  "Accept a single exact target, one idempotency key and a bounded comment."
  [{:github/keys [repo number body request-id] :as request}]
  (and (map? request)
       (= (set (keys request)) #{:actor/did :credential/label :github/repo :github/number :github/body :github/request-id})
       (string? (:actor/did request)) (<= 1 (count (:actor/did request)) 1024)
       (string? (:credential/label request)) (<= 1 (count (:credential/label request)) 128)
       (string? repo) (<= (count repo) 217)
       (re-matches #"[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+" repo)
       (not (#{"." ".."} (last (str/split repo #"/"))))
       (integer? number) (<= 1 number 2147483647)
       (event/uuid-string? request-id)
       (string? body) (not (str/blank? body)) (<= (count body) 16384)))

(defn scope [repo]
  {:scope/repos [repo] :scope/permissions comment-permissions})

(defn eligible-credential
  "Current actor, caller, credential and scoped GitHub write authority must agree."
  [state caller {:actor/keys [did] :credential/keys [label] :github/keys [repo]} at]
  (let [credential (identity/usable-credential state did label)
        actor-policy (identity/policy-of state did)]
    (when (and (identity/live? state caller at)
               (identity/live? state did at)
               (or (= caller did) (identity/may-manage? state caller did))
               (= :github-app (get-in credential [:resource/location :credential/type]))
               (law/action-allowed? actor-policy :comment {:own? false})
               (law/scope-within? (scope repo) (update actor-policy :policy/repos #(into #{} (map str/lower-case) %))))
      credential)))

(defn operation-stream [{:actor/keys [did] :github/keys [request-id]}]
  (str "github-comment:" did ":" (str/lower-case request-id)))

(defn previous-operation
  "A complete intent suppresses another POST even if no receipt was saved."
  [lg request]
  (let [[intent receipt] (ledger/stream lg (operation-stream request))]
    (when intent {:fingerprint (get-in intent [:event/body :request-sha256])
                  :receipt (:event/body receipt)})))

(defn decision
  "Record the comment policy and, when allowed, a body-free provider intent."
  [lg state caller request fingerprint eligible? {:keys [at] :as ids}]
  (let [operation (operation-stream request)]
    (policy/act lg state {:actor caller :resource operation :action :comment :at at}
                (when eligible?
                  {:stream operation :type :axxium.github/comment-intended :actor caller
                   :body {:request-sha256 fingerprint
                          :repo (:github/repo request) :number (:github/number request)
                          :actor (:actor/did request)}})
                ids (constantly eligible?))))

(defn record-receipt
  "Append verified provider evidence as a consequence of the durable intent."
  [lg caller intent receipt {:keys [effect-id at]}]
  (first (ledger/emit lg {:id effect-id :stream (:event/stream intent)
                          :type :axxium.github/comment-posted :actor caller :at at
                          :causes [(:event/id intent)] :body receipt})))
