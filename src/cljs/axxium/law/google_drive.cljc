(ns axxium.law.google-drive
  "Contracts for the Google Drive resource. Any user connects their own
   Google account through Axxium's Cloud OAuth client. Only the drive.file
   scope is requested (files this app created or was handed), with offline
   access so a refresh token is issued. There is no delete and put never
   overwrites (law 9)."
  (:require [clojure.string :as str]
            [malli.core :as m]))

(def drive-file-scope "https://www.googleapis.com/auth/drive.file")

(def scopes [drive-file-scope])

(def authorization-endpoint "https://accounts.google.com/o/oauth2/v2/auth")

(def callback-path "/api/drive/callback")

(def request-timeout-ms 15000)

(defn authorization-params
  "Query parameters for the connect redirect. `challenge` is the S256 PKCE
   challenge; `state` binds the callback to the connecting actor."
  [{:keys [client-id redirect-uri state challenge]}]
  {"client_id" client-id
   "redirect_uri" redirect-uri
   "response_type" "code"
   "scope" (apply str (interpose " " scopes))
   "access_type" "offline"
   "prompt" "consent"
   "include_granted_scopes" "false"
   "state" state
   "code_challenge" challenge
   "code_challenge_method" "S256"})

(defn granted-scopes-ok?
  "Admit a token response only when Google granted exactly drive.file."
  [scope-string]
  (and (string? scope-string)
       (= #{drive-file-scope}
          (set (remove #{""} (str/split scope-string #" "))))))

(def FileName [:string {:min 1 :max 1024}])

(def PutRequest
  [:map {:closed true}
   [:drive/name FileName]
   [:drive/folder-id {:optional true} [:string {:min 1}]]
   [:drive/mime-type {:optional true} [:string {:min 1}]]
   [:drive/content :string]])

(def GetRequest
  [:map {:closed true} [:drive/file-id [:string {:min 1}]]])

(def ListRequest
  [:map {:closed true}
   [:drive/folder-id {:optional true} [:string {:min 1}]]
   [:drive/page-token {:optional true} [:string {:min 1}]]])

(defn valid-put? [r] (m/validate PutRequest r))
(defn valid-get? [r] (m/validate GetRequest r))
(defn valid-list? [r] (m/validate ListRequest r))

(defn query-literal
  "Escape a value for a Drive `q` string literal."
  [value]
  (-> value (str/replace "\\" "\\\\") (str/replace "'" "\\'")))

(defn same-name-query
  "Drive search for an existing, untrashed file with this name in the folder."
  [{:drive/keys [name folder-id]}]
  (str "name = '" (query-literal name) "' and trashed = false"
       (when folder-id (str " and '" (query-literal folder-id) "' in parents"))))

(defn put-plan
  "Law 9: refuse when a same-named file already exists; otherwise create."
  [existing request]
  (if (seq existing)
    {:drive/outcome :refused
     :drive/reason :exists
     :drive/existing (mapv :drive/file-id existing)}
    {:drive/outcome :create :drive/request request}))

;; ---------------------------------------------------------------- resources

(def credential-kind :resource/google-drive-credential)
(def file-kind :resource/google-drive-file)

(defn credential-resource-id
  "One Drive credential per owner DID."
  [owner]
  (str "google-drive-credential:" owner))

(defn file-resource-id
  "A Drive file is addressed by its Drive id, which Drive never reuses."
  [file-id]
  (str "google-drive:" file-id))

(defn actor-did
  "did:web for a Google-signed-in Axxium actor on this instance:
   did:web:<host>:actors:<actor-id>. The port, if any, is %3A-encoded."
  [public-base-url actor-id]
  (let [host (-> public-base-url
                 (str/replace #"^https?://" "")
                 (str/replace #"/.*$" "")
                 str/lower-case
                 (str/replace ":" "%3A"))]
    (str "did:web:" host ":actors:" actor-id)))

(def EventRef
  "An event a Drive file refers to: a clio/Mongo event id."
  [:string {:min 1 :max 256}])

(def PutValueRequest
  "An MCP put: any EDN value, stored losslessly as EDN text."
  [:map {:closed true}
   [:drive/name FileName]
   [:drive/folder-id {:optional true} [:string {:min 1}]]
   [:drive/refs {:optional true} [:vector EventRef]]
   [:drive/value :any]])

(def GetValueRequest
  [:map {:closed true} [:resource/id [:string {:min 1}]]])

(def ListValueRequest
  [:map {:closed true} [:drive/ref {:optional true} EventRef]])

(defn valid-put-value? [r] (m/validate PutValueRequest r))
(defn valid-get-value? [r] (m/validate GetValueRequest r))
(defn valid-list-value? [r] (m/validate ListValueRequest r))

(def edn-mime-type "application/edn")
