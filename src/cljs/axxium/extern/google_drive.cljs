(ns axxium.extern.google-drive
  "Google Drive REST boundary. `fetch` is injected so tests never touch the
   network. Deliberately exposes no delete, trash or overwrite call."
  (:require [clojure.string :as str]))

(def token-endpoint "https://oauth2.googleapis.com/token")
(def files-endpoint "https://www.googleapis.com/drive/v3/files")
(def upload-endpoint "https://www.googleapis.com/upload/drive/v3/files")

(defn- form [m]
  (str/join "&" (for [[k v] m] (str (js/encodeURIComponent k) "=" (js/encodeURIComponent v)))))

(defn- json-body [resp]
  (-> (.json resp)
      (.then (fn [body]
               (if (.-ok resp)
                 (js->clj body :keywordize-keys true)
                 (throw (ex-info "google drive request failed"
                                 {:status (.-status resp)})))))))

(defn- token-request [fetch params]
  (-> (fetch token-endpoint
             #js {:method "POST"
                  :headers #js {"content-type" "application/x-www-form-urlencoded"}
                  :body (form params)})
      (.then json-body)))

(defn exchange-code
  [fetch {:keys [client-id client-secret redirect-uri code verifier]}]
  (token-request fetch {"grant_type" "authorization_code" "code" code
                        "client_id" client-id "client_secret" client-secret
                        "redirect_uri" redirect-uri "code_verifier" verifier}))

(defn refresh-access-token
  [fetch {:keys [client-id client-secret refresh-token]}]
  (-> (token-request fetch {"grant_type" "refresh_token" "refresh_token" refresh-token
                            "client_id" client-id "client_secret" client-secret})
      (.then #(:access_token %))))

(defn- auth [access-token] #js {"authorization" (str "Bearer " access-token)})

(defn- ->file [f]
  {:drive/file-id (:id f) :drive/name (:name f)
   :drive/mime-type (:mimeType f) :drive/parents (vec (:parents f))})

(defn list-files
  [fetch access-token {:keys [q page-token]}]
  (let [qs (form (cond-> {"fields" "nextPageToken,files(id,name,mimeType,parents)"
                          "pageSize" "100" "spaces" "drive"}
                   q (assoc "q" q)
                   page-token (assoc "pageToken" page-token)))]
    (-> (fetch (str files-endpoint "?" qs) #js {:headers (auth access-token)})
        (.then json-body)
        (.then (fn [b] {:drive/files (mapv ->file (:files b))
                        :drive/next-page-token (:nextPageToken b)})))))

(defn create-file
  "Multipart create. Always a new file id; never PATCHes an existing one."
  [fetch access-token {:drive/keys [name folder-id mime-type content]}]
  (let [boundary (str "axxium" (.toString (js/Date.now) 36))
        meta (cond-> {:name name}
               mime-type (assoc :mimeType mime-type)
               folder-id (assoc :parents [folder-id]))
        body (str "--" boundary "\r\ncontent-type: application/json; charset=UTF-8\r\n\r\n"
                  (js/JSON.stringify (clj->js meta))
                  "\r\n--" boundary "\r\ncontent-type: " (or mime-type "text/plain") "\r\n\r\n"
                  content "\r\n--" boundary "--")]
    (-> (fetch (str upload-endpoint "?uploadType=multipart&fields=id,name,mimeType,parents")
               #js {:method "POST"
                    :headers (js/Object.assign (auth access-token)
                                               #js {"content-type" (str "multipart/related; boundary=" boundary)})
                    :body body})
        (.then json-body)
        (.then ->file))))

(defn get-file
  [fetch access-token file-id]
  (let [id (js/encodeURIComponent file-id)]
    (-> (fetch (str files-endpoint "/" id "?fields=id,name,mimeType,parents") #js {:headers (auth access-token)})
        (.then json-body)
        (.then (fn [f]
                 (-> (fetch (str files-endpoint "/" id "?alt=media") #js {:headers (auth access-token)})
                     (.then (fn [r] (if (.-ok r) (.text r)
                                        (throw (ex-info "google drive download failed" {:status (.-status r)})))))
                     (.then (fn [text] (assoc (->file f) :drive/content text)))))))))

(defn authorize-url
  "Google consent URL for `axxium.law.google-drive/authorization-params`."
  [authorization-endpoint params]
  (str authorization-endpoint "?" (form params)))
