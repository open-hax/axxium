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
  (if-let [refresh (some->> (drive/sealed-credential st owner)
                            (tc/open-sealed token-key (drive/credential-aad owner)))]
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

(defn- grant-binding [key st owner]
  (tc/fingerprint key :drive/actor-grant (drive/sealed-credential st owner)))

(defn- ^:async reserve! [{:keys [fetch token-key ids] :as deps} lg
                         {:keys [actor-did intent-id request-binding] :as request} authorize?]
  (let [st (state/fold lg)
        owner (drive/actor-owner actor-did)]
    (if-let [intent (drive/actor-put-intent st owner intent-id)]
      (if (= request-binding (:drive/request-binding intent))
        {:result intent}
        {:error :idempotency-conflict})
      (let [at (await (access-token deps st owner))
            file-id (await (gd/generate-file-id fetch at))]
        (if (authorize?)
          (let [out (drive/reserve-actor-put lg st
                      (assoc request :file-id file-id :grant-binding (grant-binding token-key st owner)) (ids))]
            (if-let [effect (:effect out)]
              {:ledger (:ledger out) :result (get-in effect [:event/body :resource/metadata])}
              {:error :reconciliation-required}))
          {:error :unauthenticated})))))

(defn- ^:async file-or-missing [fetch at file-id]
  (try
    (await (gd/get-file fetch at file-id))
    (catch :default e
      (when-not (= 404 (:status (ex-data e))) (throw e)))))

(defn- matches-upload? [file file-id name content]
  (and (= file-id (:drive/file-id file)) (= name (:drive/name file))
       (= law/edn-mime-type (:drive/mime-type file)) (= content (:drive/content file))))

(defn- ^:async create-reserved! [fetch at st owner file-id args content authorize?]
  (try
    (let [name (:drive/name args)
          matches (await (find-existing fetch at (law/same-name-query args)))]
      (if (seq matches)
        {:error (:drive/reason (drive/put-plan st owner matches args))}
        (if (authorize?)
          (let [file (await (gd/create-file fetch at {:drive/file-id file-id :drive/name name
                                                      :drive/mime-type law/edn-mime-type :drive/content content}))]
            (if (and (= file-id (:drive/file-id file)) (= name (:drive/name file))
                     (= law/edn-mime-type (:drive/mime-type file)))
              {:result file}
              {:error :reconciliation-required}))
          {:error :unauthenticated})))
    (catch :default e
      (if (= 409 (:status (ex-data e)))
        (let [file (await (file-or-missing fetch at file-id))]
          (if (matches-upload? file file-id (:drive/name args) content)
            {:result file}
            {:error :reconciliation-required}))
        {:error :reconciliation-required}))))

(defn- ^:async finish-reserved! [{:keys [fetch ids token-key sync-ledger!] :as deps} lg
                                {:keys [actor-did intent-id]} args content
                                {:drive/keys [file-id] :as intent} authorize?]
  (let [st (state/fold lg)
        owner (drive/actor-owner actor-did)
        registered (drive/owned-file st owner (law/file-resource-id file-id))]
    (cond
      registered
      (do (sync-ledger! lg) {:result registered})

      (not= (:drive/grant-binding intent) (grant-binding token-key st owner))
      {:error :reconciliation-required}

      :else
      (let [_ (sync-ledger! lg)
            at (await (access-token deps st owner))
            existing (await (file-or-missing fetch at file-id))
            {:keys [result error]} (if existing
                                    (if (matches-upload? existing file-id (:drive/name args) content)
                                      {:result existing}
                                      {:error :reconciliation-required})
                                    (await (create-reserved! fetch at st owner file-id args content authorize?)))]
        (cond
          error {:error error}
          (not (authorize?)) {:error :unauthenticated}
          :else
          (let [out (drive/complete-actor-put lg st owner intent-id result (:drive/refs args []) (ids))]
            (if-let [effect (:effect out)]
              {:ledger (:ledger out) :result (:event/body effect)}
              {:error :reconciliation-required})))))))

(defn ^:async actor-put!
  "Durably reserve a Google-generated ID before attempting an actor's upload.
   A retry reconciles that ID and cannot allocate a second Google file."
  [{:keys [token-key] :as deps} {:keys [actor-did] :as principal} args serialize! authorize?]
  (if-not (law/valid-actor-put-value? args)
    {:error :invalid}
    (let [content (pr-str (:drive/value args))]
      (if (> (js/Buffer.byteLength content "utf8") law/actor-put-max-bytes)
        {:error :invalid}
        (try
          (let [intent-id (str "google-drive-put:" (tc/stable-digest :drive/actor-put-key
                                                       [actor-did (:drive/idempotency-key args)]))
                request (assoc principal :intent-id intent-id
                          :request-binding (tc/fingerprint token-key :drive/actor-put-request
                                             [(:drive/name args) (:drive/refs args []) content]))
                {:keys [result error]} (await (serialize! #(reserve! deps % request authorize?)))]
            (if error
              {:error error}
              (await (serialize! #(finish-reserved! deps % request args content result authorize?)))))
          (catch :default e
            {:error (or (:axxium/error (ex-data e)) :reconciliation-required)}))))))

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
