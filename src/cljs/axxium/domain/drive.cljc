(ns axxium.domain.drive
  "Google Drive as Axxium resources (pure). Two kinds:

   - :resource/google-drive-credential, one per owner DID, whose location
     holds the AES-GCM sealed refresh token (never plaintext);
   - :resource/google-drive-file, one per file Axxium created, addressed by
     `google-drive:<file-id>`, carrying the event ids it refers to so a
     clio/Mongo event can find its Drive files by resource id.

   Both are registered through `resource/register`, so every registration
   records its policy decision before its effect (law 7)."
  (:require [axxium.domain.resource :as resource]
            [axxium.law.google-drive :as law]))

(defn credential-resource [owner sealed-refresh-token]
  {:resource/id (law/credential-resource-id owner)
   :resource/kind law/credential-kind
   :resource/owner owner
   :resource/store :axxium
   :resource/location {:drive/sealed-refresh-token sealed-refresh-token}
   :resource/metadata {:drive/scope law/drive-file-scope}})

(defn file-resource [owner {:drive/keys [file-id name mime-type parents]} refs]
  {:resource/id (law/file-resource-id file-id)
   :resource/kind law/file-kind
   :resource/owner owner
   :resource/store :google-drive
   :resource/location {:drive/file-id file-id}
   :resource/metadata (cond-> {:drive/name name :drive/refs (vec refs)}
                        mime-type (assoc :drive/mime-type mime-type)
                        (seq parents) (assoc :drive/parents (vec parents)))})

(defn connect
  "Register (or rotate) the owner's sealed Drive credential."
  [lg state owner sealed ids]
  (resource/register lg state owner (credential-resource owner sealed) ids))

(defn record-put
  "Register a file Axxium just created in the owner's Drive."
  [lg state owner file refs ids]
  (resource/register lg state owner (file-resource owner file refs) ids))

(defn sealed-credential [state owner]
  (get-in state [:resources (law/credential-resource-id owner)
                 :resource/location :drive/sealed-refresh-token]))

(defn owned-file
  "The owner's Drive file resource by id, or nil (absent or not theirs)."
  [state owner resource-id]
  (let [r (get-in state [:resources resource-id])]
    (when (and (= law/file-kind (:resource/kind r)) (= owner (:resource/owner r)))
      r)))

(defn owned-files
  "The owner's Drive file resources, optionally only those referring to
   event `ref`, sorted by resource id."
  ([state owner] (owned-files state owner nil))
  ([state owner ref]
   (->> (vals (:resources state))
        (filter #(and (= law/file-kind (:resource/kind %)) (= owner (:resource/owner %))))
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
