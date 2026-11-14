(ns axxium.domain.drive
  "Google Drive as Axxium resources (pure). Two kinds:

   - :resource/google-drive-credential, one per owner DID, whose location
     holds the AES-GCM sealed refresh token (never plaintext);
   - :resource/google-drive-file, one per file Axxium created, addressed by
     `google-drive:<file-id>`, carrying the event ids it refers to so a
     clio/Mongo event can find its Drive files by resource id.

   Both are registered through `resource/register`, so every registration
   records its policy decision before its effect (law 7)."
  (:require [axxium.domain.github-identity :as identity]
            [axxium.domain.policy :as policy]
            [axxium.domain.resource :as resource]
            [axxium.law.google-drive :as law]))

(defn actor-owner [did]
  {:drive/owner-kind :managed-actor :drive/did did})

(defn- owner-did [owner]
  (if (string? owner) owner (:drive/did owner)))

(defn- owner-kind [owner]
  (when-not (string? owner) (:drive/owner-kind owner)))

(defn- mark-owner [resource owner]
  (cond-> resource
    (owner-kind owner) (update :resource/metadata assoc :drive/owner-kind (owner-kind owner))))

(defn- owned-by? [resource owner]
  (and (some? resource) (= (owner-did owner) (:resource/owner resource))
       (= (owner-kind owner) (get-in resource [:resource/metadata :drive/owner-kind]))))

(defn credential-aad
  "Namespace managed credentials apart from browser credentials with the same DID."
  [owner]
  (if (string? owner) owner (str "google-drive:managed-actor:" (owner-did owner))))

(defn- credential-id [owner]
  (law/credential-resource-id (credential-aad owner)))

(defn credential-resource [owner sealed-refresh-token]
  (mark-owner {:resource/id (credential-id owner)
                :resource/kind law/credential-kind
                :resource/owner (owner-did owner)
                :resource/store :axxium
                :resource/location {:drive/sealed-refresh-token sealed-refresh-token}
                :resource/metadata {:drive/scope law/drive-file-scope}}
    owner))

(defn file-resource [owner {:drive/keys [file-id name mime-type parents]} refs]
  (mark-owner {:resource/id (law/file-resource-id file-id)
                :resource/kind law/file-kind
                :resource/owner (owner-did owner)
                :resource/store :google-drive
                :resource/location {:drive/file-id file-id}
                :resource/metadata (cond-> {:drive/name name :drive/refs (vec refs)}
                                     mime-type (assoc :drive/mime-type mime-type)
                                     (seq parents) (assoc :drive/parents (vec parents)))}
    owner))

(defn connect
  "Register (or rotate) the owner's sealed Drive credential."
  [lg state owner sealed ids]
  (resource/register lg state owner (credential-resource owner sealed) ids))

(defn eligible-actor?
  "Require an active, non-session managed actor under the configured human root."
  [identity-state root-did actor-did now]
  (and (identity/active-owner-delegation? identity-state root-did actor-did)
       (contains? #{:agent :bot} (identity/kind-of identity-state actor-did))
       (identity/live? identity-state actor-did now)))

(defn connect-actor
  "Record the human operator's authorization before registering a sealed
   credential owned by the selected actor, with its root binding provenance."
  [lg drive-state identity-state {:keys [root-did operator-id actor-did]} sealed {:keys [at] :as ids}]
  (let [r (-> (credential-resource (actor-owner actor-did) sealed)
              (update :resource/metadata assoc :drive/root-did root-did :drive/operator-id operator-id))
        allowed? (and (seq operator-id) (eligible-actor? identity-state root-did actor-did at))]
    (policy/act lg drive-state
      {:actor root-did :resource (:resource/id r) :action :connect-drive :at at}
      (when allowed? {:stream "resources" :type :axxium.resource/registered :actor root-did :body r})
      ids (constantly allowed?))))

(defn actor-connected?
  "Use only an exact actor grant recorded for the current owner binding."
  [state root-did operator-id actor-did]
  (let [owner (actor-owner actor-did)
        r (get-in state [:resources (credential-id owner)])]
    (and (= law/credential-kind (:resource/kind r))
         (owned-by? r owner)
         (= root-did (get-in r [:resource/metadata :drive/root-did]))
         (= operator-id (get-in r [:resource/metadata :drive/operator-id]))
         (seq (get-in r [:resource/location :drive/sealed-refresh-token])))))

(defn record-put
  "Register a file Axxium just created in the owner's Drive."
  [lg state owner file refs ids]
  (let [r (file-resource owner file refs)]
    (when-not (get-in state [:resources (:resource/id r)])
      (resource/register lg state (owner-did owner) r ids))))

(defn sealed-credential [state owner]
  (let [r (get-in state [:resources (credential-id owner)])]
    (when (and (= law/credential-kind (:resource/kind r)) (owned-by? r owner))
      (get-in r [:resource/location :drive/sealed-refresh-token]))))

(defn owned-file
  "The owner's Drive file resource by id, or nil (absent or not theirs)."
  [state owner resource-id]
  (let [r (get-in state [:resources resource-id])]
    (when (and (= law/file-kind (:resource/kind r)) (owned-by? r owner))
      r)))

(defn actor-put-intent
  "Return only a reservation made by this actor for this request key."
  [state owner intent-id]
  (let [r (get-in state [:resources intent-id])]
    (when (and (owned-by? r owner) (= law/put-intent-kind (:resource/kind r)))
      (:resource/metadata r))))

(defn reserve-actor-put
  "Record the actor's allowing decision and its reserved external ID before upload."
  [lg state {:keys [root-did operator-id actor-did intent-id file-id request-binding grant-binding]} {:keys [at] :as ids}]
  (let [allowed? (and (law/file-id? file-id)
                      (actor-connected? state root-did operator-id actor-did)
                      (nil? (get-in state [:resources intent-id]))
                      (nil? (get-in state [:resources (law/file-resource-id file-id)])))
        intent (mark-owner {:resource/id intent-id :resource/owner actor-did
                            :resource/kind law/put-intent-kind :resource/store :axxium
                            :resource/metadata {:drive/file-id file-id :drive/request-binding request-binding
                                                :drive/grant-binding grant-binding}}
                 (actor-owner actor-did))]
    (when allowed?
      (policy/act lg state
        {:actor actor-did :resource intent-id :action :drive-create :at at}
        {:stream "resources" :type :axxium.resource/registered :actor actor-did :body intent}
        ids (constantly true)))))

(defn complete-actor-put
  "Register only an actor's reserved file ID. Do not replace an existing resource."
  [lg state owner intent-id file refs ids]
  (let [intent (actor-put-intent state owner intent-id)
        file-id (:drive/file-id file)]
    (when (and intent (= file-id (:drive/file-id intent)))
      (record-put lg state owner file refs ids))))

(defn owned-files
  "The owner's Drive file resources, optionally only those referring to
   event `ref`, sorted by resource id."
  ([state owner] (owned-files state owner nil))
  ([state owner ref]
   (->> (vals (:resources state))
        (filter #(and (= law/file-kind (:resource/kind %)) (owned-by? % owner)))
        (filter #(or (nil? ref) (some #{ref} (get-in % [:resource/metadata :drive/refs]))))
        (sort-by :resource/id)
        vec)))

(defn put-plan
  "Refuse same-name files; distinguish files missing from the owner's registry.
   A matching name alone cannot authorize adopting an external file."
  [state owner existing request]
  (let [plan (law/put-plan existing request)]
    (if (some #(nil? (owned-file state owner (law/file-resource-id (:drive/file-id %)))) existing)
      (assoc plan :drive/reason :exists-untracked)
      plan)))
