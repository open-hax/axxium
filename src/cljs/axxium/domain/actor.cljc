(ns axxium.domain.actor
  "Actors with DIDs. Every actor is registered by an event on the `actors`
   stream. Membership in a group, team or org is a grant of :member on the
   collective's actor resource (law 4), so it lives on the `grants` stream."
  (:require [axxium.law.did :as did]
            [malli.core :as m]))

(def kinds #{:human :agent :process :group :team :org})

(def collective-kinds #{:group :team :org})

(def ActorContract
  [:map {:closed true}
   [:actor/did did/Did]
   [:actor/kind (into [:enum] (sort kinds))]
   [:actor/name [:string {:min 1 :max 200}]]])

(defn valid-actor? [actor]
  (m/validate ActorContract actor))

(defn actor-resource
  "The resource id that stands for an actor, used for membership grants."
  [actor-did]
  (str "actor:" actor-did))

(defn registration
  "Event spec registering `actor`, asserted by `registrar`."
  [{:keys [id at registrar]} actor]
  (when-not (valid-actor? actor)
    (throw (ex-info "Invalid actor"
                    {:axxium/error :axxium.actor/invalid
                     :explain (m/explain ActorContract actor)})))
  {:id id :stream "actors" :type :axxium.actor/registered
   :actor registrar :at at :body actor})

(defn step
  "Fold one event into the actor projection."
  [state event]
  (case (:event/type event)
    :axxium.actor/registered
    (let [actor (:event/body event)]
      (assoc-in state [:actors (:actor/did actor)] actor))
    state))

(defn collective? [state actor-did]
  (contains? collective-kinds (get-in state [:actors actor-did :actor/kind])))
