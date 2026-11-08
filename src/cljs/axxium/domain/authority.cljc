(ns axxium.domain.authority
  "Grants and revokes (Katamorph shapes) folded into authority.
   Law 5: authority is inherited through collective membership, transitively
   and cycle safe. Law 6: revokes only remove authority."
  (:require [axxium.domain.actor :as actor]
            [axxium.law.did :as did]
            [malli.core :as m]))

(def GrantContract
  [:map {:closed false}
   [:grant/id [:string {:min 1}]]
   [:grant/actor did/Did]
   [:grant/resource [:string {:min 1}]]
   [:grant/actions [:vector {:min 1} :keyword]]
   [:grant/at {:optional true} inst?]
   [:grant/expires {:optional true} inst?]])

(def RevokeContract
  [:map {:closed false}
   [:revoke/id [:string {:min 1}]]
   [:revoke/actor did/Did]
   [:revoke/resource [:string {:min 1}]]
   [:revoke/actions {:optional true} [:vector :keyword]]
   [:revoke/at inst?]])

(defn grant-event
  [{:keys [id at granter]} grant]
  (when-not (m/validate GrantContract grant)
    (throw (ex-info "Invalid grant" {:axxium/error :axxium.grant/invalid
                                     :explain (m/explain GrantContract grant)})))
  {:id id :stream "grants" :type :axxium.grant/granted
   :actor granter :at at :body grant})

(defn revoke-event
  [{:keys [id at revoker]} revoke]
  (when-not (m/validate RevokeContract revoke)
    (throw (ex-info "Invalid revoke" {:axxium/error :axxium.grant/invalid-revoke
                                      :explain (m/explain RevokeContract revoke)})))
  {:id id :stream "grants" :type :axxium.grant/revoked
   :actor revoker :at at :body revoke})

(defn membership-grant
  "Law 4: membership of `member` in `collective` is a :member grant."
  [grant-id member collective]
  {:grant/id grant-id
   :grant/actor member
   :grant/resource (actor/actor-resource collective)
   :grant/actions [:member]})

(defn- apply-revoke [grants {:revoke/keys [actor resource actions]}]
  (into {}
        (keep (fn [[gid g]]
                (if (and (= actor (:grant/actor g)) (= resource (:grant/resource g)))
                  (let [left (if actions
                               (vec (remove (set actions) (:grant/actions g)))
                               [])]
                    (when (seq left) [gid (assoc g :grant/actions left)]))
                  [gid g])))
        grants))

(defn step
  [state event]
  (case (:event/type event)
    :axxium.grant/granted
    (let [g (:event/body event)] (assoc-in state [:grants (:grant/id g)] g))
    :axxium.grant/revoked
    (update state :grants apply-revoke (:event/body event))
    state))

(defn- live? [grant at]
  (or (nil? (:grant/expires grant))
      (< (inst-ms at) (inst-ms (:grant/expires grant)))))

(defn direct?
  [state actor-did resource action at]
  (boolean
   (some (fn [g] (and (= actor-did (:grant/actor g))
                      (= resource (:grant/resource g))
                      (some #{action} (:grant/actions g))
                      (live? g at)))
         (vals (:grants state)))))

(defn collectives-of
  "Every collective `actor-did` belongs to, directly or through nesting."
  [state actor-did at]
  (loop [frontier [actor-did] seen #{}]
    (if-let [d (first frontier)]
      (let [parents (->> (vals (:grants state))
                         (filter #(and (= d (:grant/actor %))
                                       (some #{:member} (:grant/actions %))
                                       (live? % at)))
                         (map #(subs (:grant/resource %) (count "actor:")))
                         (remove seen))]
        (recur (into (vec (rest frontier)) parents) (into seen parents)))
      seen)))

(defn members
  "Direct members of `collective`."
  [state collective at]
  (into #{}
        (comp (filter #(and (= (actor/actor-resource collective) (:grant/resource %))
                            (some #{:member} (:grant/actions %))
                            (live? % at)))
              (map :grant/actor))
        (vals (:grants state))))

(defn allowed?
  "Law 5."
  [state actor-did resource action at]
  (boolean
   (some #(direct? state % resource action at)
         (cons actor-did (collectives-of state actor-did at)))))
