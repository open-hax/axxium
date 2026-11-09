(ns axxium.extern.drive-mcp
  "MCP tools over the Google Drive resource: drive_put, drive_get, drive_list.

   (call deps ctx tool args) -> Promise<{:ledger :result} | {:error ...}>.
   deps = {:fetch :token-key :client {:client-id :client-secret}
           :ledger :ids (fn [] {:decision-id :effect-id :at})}
   ctx  = {:owner <did>}.

   Values travel as EDN text (application/edn) and are read back with the
   clio EDN reader, so put → get is lossless for any EDN value, including
   float embedding vectors. Put never overwrites; there is no delete. The
   caller persists the returned ledger (e.g. `ledger-file/append-batch!`)."
  (:require [axxium.domain.drive :as drive]
            [axxium.domain.state :as state]
            [axxium.extern.google-drive :as gd]
            [axxium.extern.token-crypto :as tc]
            [axxium.law.google-drive :as law]
            [clio.shape.edn :as edn]))

(def tools #{"drive_put" "drive_get" "drive_list"})

(defn- refuse [error] (js/Promise.resolve {:error error}))

(defn- access-token
  "Open the owner's sealed refresh token and trade it for an access token."
  [{:keys [fetch token-key client]} st owner]
  (if-let [refresh (some->> (drive/sealed-credential st owner) (tc/open-sealed token-key owner))]
    (-> (gd/refresh-access-token fetch (assoc client :refresh-token refresh))
        (.catch (fn [error]
                  (if (= "invalid_grant" (:oauth-error (ex-data error)))
                    (throw (ex-info "drive not connected" {:axxium/error :not-connected}))
                    (throw error)))))
    (js/Promise.reject (ex-info "drive not connected" {:axxium/error :not-connected}))))

(defn- create! [{:keys [fetch ledger ids]} st owner at args]
  (-> (gd/create-file fetch at (cond-> {:drive/name (:drive/name args)
                                        :drive/mime-type law/edn-mime-type
                                        :drive/content (pr-str (:drive/value args))}
                                 (:drive/folder-id args) (assoc :drive/folder-id (:drive/folder-id args))))
      (.then (fn [file]
               (let [{lg :ledger effect :effect}
                     (drive/record-put ledger st owner file (:drive/refs args []) (ids))]
                 (if effect
                   {:ledger lg :result (:event/body effect)}
                   {:error :created-untracked :drive/reason :forbidden
                    :drive/existing [(:drive/file-id file)]}))))))

(defn- find-existing
  "Find a same-name page or prove exhaustion within one search deadline."
  [fetch at q]
  (let [signal (js/AbortSignal.timeout law/request-timeout-ms)]
    (letfn [(search [page-token seen]
              (.throwIfAborted signal)
              (-> (gd/list-files fetch at {:q q :page-token page-token :signal signal})
                  (.then (fn [{:drive/keys [files next-page-token incomplete-search?]}]
                           (.throwIfAborted signal)
                           (cond
                             (seq files) files
                             incomplete-search? (throw (ex-info "incomplete Drive duplicate search" {}))
                             (nil? next-page-token) files
                             (and (string? next-page-token)
                                  (seq next-page-token)
                                  (not (contains? seen next-page-token)))
                             (search next-page-token (conj seen next-page-token))
                             :else (throw (ex-info "invalid Drive continuation token" {})))))))]
      (search nil #{}))))

(defn- ^:async put! [{:keys [fetch] :as deps} st owner args]
  (let [request (select-keys args [:drive/name :drive/folder-id])
        at (await (access-token deps st owner))
        files (await (find-existing fetch at (law/same-name-query request)))
        plan (drive/put-plan st owner files request)]
    (if (= :refused (:drive/outcome plan))
      {:error (:drive/reason plan) :drive/existing (:drive/existing plan)}
      (create! deps st owner at args))))

(defn- get! [{:keys [fetch] :as deps} st owner {:resource/keys [id]}]
  (if-let [r (drive/owned-file st owner id)]
    (-> (access-token deps st owner)
        (.then #(gd/get-file fetch % (get-in r [:resource/location :drive/file-id])))
        (.then (fn [{:drive/keys [content]}]
                 {:result {:resource/id id
                           :resource/metadata (:resource/metadata r)
                           :drive/value (edn/read-one content)}})))
    (refuse :not-found)))

(defn- list! [st owner {:drive/keys [ref]}]
  (js/Promise.resolve {:result {:resources (drive/owned-files st owner ref)}}))

(defn call
  [{:keys [ledger] :as deps} {:keys [owner]} tool args]
  (let [st (state/fold ledger)]
    (-> (cond
          (nil? owner) (refuse :unauthenticated)
          (not (contains? tools tool)) (refuse :unknown-tool)
          (= tool "drive_put") (if (law/valid-put-value? args) (put! deps st owner args) (refuse :invalid))
          (= tool "drive_get") (if (law/valid-get-value? args) (get! deps st owner args) (refuse :invalid))
          :else (if (law/valid-list-value? args) (list! st owner args) (refuse :invalid)))
        (.catch (fn [e] {:error (or (:axxium/error (ex-data e)) :drive-unavailable)})))))
