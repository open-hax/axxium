(ns axxium.extern.http
  "Fastify request and reply conversion for account routes.")

(defn body [req]
  (js->clj (or (aget req "body") #js {}) :keywordize-keys true))

(defn param [req key]
  (aget (aget req "params") key))

(defn query-param [req key]
  (aget (aget req "query") key))

(defn send! [reply status data]
  (.send (.code reply status) (clj->js data)))

(defn send-secret! [reply status data]
  (.header reply "Cache-Control" "no-store")
  (.header reply "Pragma" "no-cache")
  (send! reply status data))

(defn redirect! [reply url]
  (.redirect reply url))

(defn cookie [req name]
  (some-> req (aget "cookies") (aget name)))

(defn authorization [req]
  (some-> req (aget "headers") (aget "authorization")))

(defn set-session-cookie! [reply name token {:keys [secure same-site max-age]}]
  (.setCookie reply name token
              #js {:path "/" :httpOnly true :secure secure
                   :sameSite same-site :maxAge max-age}))

(defn clear-session-cookie! [reply name]
  (.clearCookie reply name #js {:path "/"}))

(defn error-message [error]
  (.-message error))

(defn parse-int [value]
  (when value
    (let [parsed (js/parseInt value 10)]
      (when-not (js/Number.isNaN parsed) parsed))))

(defn set-state-cookie!
  ([reply name value secure]
   (set-state-cookie! reply name value secure "/api/auth/google/callback"))
  ([reply name value secure path]
   (.setCookie reply name value
               #js {:path path :httpOnly true
                    :sameSite "lax" :secure secure :maxAge 300})))

(defn clear-state-cookie!
  ([reply name]
   (clear-state-cookie! reply name "/api/auth/google/callback"))
  ([reply name path]
   (.clearCookie reply name #js {:path path})))

(defn request-url [req]
  (some-> req (aget "raw") (aget "url")))

(defn get! [app path handler]
  (.get app path handler))

(defn post! [app path handler]
  (.post app path handler))

(defn delete! [app path handler]
  (.delete app path handler))
