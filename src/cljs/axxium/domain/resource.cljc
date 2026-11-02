(ns axxium.domain.resource
  "Resource registry as a projection of `resources` events. Resources use
   Katamorph's Resource shape. Registration and grant issuance both go
   through policy/act, so each decision is recorded before its effect."
  (:require [axxium.domain.authority :as authority]
            [axxium.domain.policy :as policy]
            [axxium.law.did :as did]
            [malli.core :as m]))

(def local-kinds
  "The simplest resources: what the Axxium host itself can expose."
  #{:file :process :program})

(def ResourceContract
  [:and
   [:map {:closed false}
    [:resource/id [:string {:min 1}]]
    [:resource/kind :qualified-keyword]
    [:resource/owner did/Did]
    [:resource/store {:optional true} [:or :keyword :string]]
    [:resource/location {:optional true} :any]
    [:resource/metadata {:optional true} [:map-of [:or :keyword :string] :any]]]
   [:fn {:error/message "a resource names :resource/store or :resource/location"}
    (fn [r] (or (some? (:resource/store r)) (some? (:resource/location r))))]])

(defn kind [k] (keyword "resource" (name k)))

(defn valid-resource? [r] (m/validate ResourceContract r))

(defn local-file
  "A file on the Axxium host. `metadata` comes from the fs extern adapter."
  [owner path metadata]
  {:resource/id (str "file:" path)
   :resource/kind (kind :file)
   :resource/owner owner
   :resource/store :local
   :resource/location {:path path}
   :resource/metadata metadata})

(defn local-process [owner pid command]
  {:resource/id (str "process:" pid)
   :resource/kind (kind :process)
   :resource/owner owner
   :resource/store :local
   :resource/location {:pid pid}
   :resource/metadata {:command command}})

(defn local-program [owner path]
  {:resource/id (str "program:" path)
   :resource/kind (kind :program)
   :resource/owner owner
   :resource/store :local
   :resource/location {:path path}})

(def registry "registry")

(defn step [state event]
  (case (:event/type event)
    :axxium.resource/registered
    (let [r (:event/body event)] (assoc-in state [:resources (:resource/id r)] r))
    :axxium.resource/moved
    (let [{:resource/keys [id store location]} (:event/body event)]
      (update-in state [:resources id] assoc :resource/store store :resource/location location))
    state))

(defn owner? [state actor-did resource-id]
  (= actor-did (get-in state [:resources resource-id :resource/owner])))

(defn- may-register?
  "An actor may register a resource it owns."
  [_state {:keys [actor owner]}]
  (= actor owner))

(defn register
  "Record a :register decision, then the registration."
  [lg state actor-did resource {:keys [decision-id effect-id at]}]
  (when-not (valid-resource? resource)
    (throw (ex-info "Invalid resource" {:axxium/error :axxium.resource/invalid
                                        :explain (m/explain ResourceContract resource)})))
  (policy/act lg state
              {:actor actor-did :resource registry :action :register :at at
               :owner (:resource/owner resource)}
              {:stream "resources" :type :axxium.resource/registered
               :actor actor-did :body resource}
              {:decision-id decision-id :effect-id effect-id :at at}
              may-register?))

(defn- may-grant?
  "The owner of a resource may grant on it."
  [state {:keys [actor resource]}]
  (owner? state actor resource))

(defn issue-grant
  "Record a :grant decision on the grant's resource, then the grant."
  [lg state granter grant {:keys [decision-id effect-id at]}]
  (let [spec (authority/grant-event {:id effect-id :at at :granter granter} grant)]
    (policy/act lg state
                {:actor granter :resource (:grant/resource grant) :action :grant :at at}
                (dissoc spec :id :at)
                {:decision-id decision-id :effect-id effect-id :at at}
                may-grant?)))

(defn issue-revoke
  [lg state revoker revoke {:keys [decision-id effect-id at]}]
  (let [spec (authority/revoke-event {:id effect-id :at at :revoker revoker} revoke)]
    (policy/act lg state
                {:actor revoker :resource (:revoke/resource revoke) :action :grant :at at}
                (dissoc spec :id :at)
                {:decision-id decision-id :effect-id effect-id :at at}
                may-grant?)))

(defn move
  "Law: moving a resource between stores is an action on it (:move)."
  [lg state actor-did resource-id store location {:keys [decision-id effect-id at]}]
  (policy/act lg state
              {:actor actor-did :resource resource-id :action :move :at at}
              {:stream "resources" :type :axxium.resource/moved :actor actor-did
               :body {:resource/id resource-id :resource/store store :resource/location location}}
              {:decision-id decision-id :effect-id effect-id :at at}
              may-grant?))
