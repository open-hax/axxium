(ns axxium.domain.github-comment
  "Authority and durable decisions for one managed GitHub comment."
  (:require [axxium.domain.github-identity :as identity]
            [axxium.domain.ledger :as ledger]
            [axxium.domain.policy :as policy]
            [axxium.law.github-identity :as law]
            [clojure.string :as str]))

(defn target-permissions
  "Require the GitHub write permission for the verified conversation type."
  [target]
  (case target
    :issue {"issues" "write"}
    :pull-request {"pull_requests" "write"}
    nil))

(defn- admitted-targets [actor-policy repo]
  (let [policy (update actor-policy :policy/repos #(into #{} (map str/lower-case) %))]
    (filterv #(law/scope-within? {:scope/repos [repo] :scope/permissions (target-permissions %)} policy)
             [:issue :pull-request])))

(defn eligible-credential
  "Current actor, caller, credential and scoped GitHub write authority must agree."
  ([state caller request at] (eligible-credential state caller request at nil))
  ([state caller {:actor/keys [did] :credential/keys [label] :github/keys [repo]} at target]
   (let [credential (identity/usable-credential state did label)
         actor-policy (identity/policy-of state did)
         targets (admitted-targets actor-policy repo)]
     (when (and (identity/live? state caller at)
                (identity/live? state did at)
                (or (= caller did) (identity/may-manage? state caller did))
                (= :github-app (get-in credential [:resource/location :credential/type]))
                (law/action-allowed? actor-policy :comment {:own? false})
                (seq targets)
                (or (nil? target) (some #{target} targets)))
       {:credential credential :targets targets}))))

(defn operation-stream [{:actor/keys [did] :github/keys [request-id]}]
  (str "github-comment:" did ":" (str/lower-case request-id)))

(defn previous-operation
  "A complete intent suppresses another POST even if no receipt was saved."
  [lg request]
  (let [[intent response] (ledger/stream lg (operation-stream request))]
    (when intent {:fingerprint (get-in intent [:event/body :request-sha256])
                  :target (get-in intent [:event/body :github/target-kind])
                  :receipt (when (= :axxium.github/comment-posted (:event/type response))
                             (:event/body response))
                  :provider-rejected? (= :axxium.github/comment-rejected (:event/type response))
                  :diagnostics (law/comment-provider-diagnostics (:event/body response))})))

(defn decision
  "Record the comment policy and, when allowed, a body-free provider intent."
  [lg state caller request fingerprint eligible? target {:keys [at] :as ids}]
  (let [operation (operation-stream request)]
    (policy/act lg state {:actor caller :resource operation :action :comment :at at}
                (when eligible?
                  {:stream operation :type :axxium.github/comment-intended :actor caller
                   :body (cond-> {:request-sha256 fingerprint
                                  :repo (:github/repo request) :number (:github/number request)
                                  :actor (:actor/did request)}
                           target (assoc :github/target-kind target))})
                ids (constantly eligible?))))

(defn record-provider-response
  "Append the shaped GitHub response evidence as a consequence of the durable intent."
  [lg caller intent outcome evidence {:keys [effect-id at]}]
  (let [type (case outcome
               :posted :axxium.github/comment-posted
               :rejected :axxium.github/comment-rejected
               :unverified :axxium.github/comment-unverified)]
    (first (ledger/emit lg {:id effect-id :stream (:event/stream intent)
                           :type type :actor caller :at at
                           :causes [(:event/id intent)] :body evidence}))))
