(ns axxium.extern.oidc-http
  "Fastify interaction transport around the durable OIDC engine."
  (:require [axxium.extern.agent-credentials :as secrets]
            [axxium.extern.oidc-provider :as engine]
            [axxium.extern.oidc-store :as store]
            [axxium.law.oidc :as law]
            [clojure.string :as str]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]))

(defn- private-config
  "Load only an operator-selected private regular JSON file; never echo values."
  [path]
  (let [stat (.statSync fs path)]
    (when-not (and (.isFile stat) (<= (.-size stat) 1048576)
                   (zero? (bit-and (.-mode stat) 63)))
      (throw (ex-info "OIDC configuration requires a private regular file" {})))
    (try
      (js->clj (js/JSON.parse (.readFileSync fs path "utf8")) :keywordize-keys true)
      (catch :default _ (throw (ex-info "Invalid OIDC configuration JSON" {}))))))

(defn- cookie-value
  "Decode one host cookie; reject ambiguity and malformed escapes."
  [request name]
  (let [matches (->> (str/split (or (.. request -headers -cookie) "") #";")
                     (map str/trim)
                     (filter #(str/starts-with? % (str name "="))))]
    (when (= 1 (count matches))
      (try (js/decodeURIComponent (subs (first matches) (inc (count name))))
           (catch :default _ nil)))))

(defn- html-escape
  "Encode untrusted client/interaction labels as plain HTML text."
  [value]
  (str/escape (str value) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn- csrf-value
  "Bind the private cookie seed to this exact interaction UID."
  [key seed uid]
  (-> (.createHmac crypto "sha256" key)
      (.update (str seed ":" uid))
      (.digest "base64url")))

(defn- csrf-valid?
  "Require exact Origin plus constant-time comparison of bounded ASCII tokens."
  [request issuer-origin cookie-name key uid]
  (let [seed (cookie-value request cookie-name)
        body (.-body request)
        supplied (when body (aget body "csrf"))]
    (and (= issuer-origin (.. request -headers -origin))
         (string? seed) (re-matches #"[A-Za-z0-9_-]{43}" seed)
         (string? supplied) (re-matches #"[A-Za-z0-9_-]{43}" supplied)
         (.timingSafeEqual crypto
           (js/Buffer.from supplied "utf8")
           (js/Buffer.from (csrf-value key seed uid) "utf8")))))

(defn- ^:async current-root [request cookie-name resolve-session!]
  (when-let [token (cookie-value request cookie-name)]
    ;; Agent credentials are not browser-root sessions.
    (when-not (str/starts-with? token "axx_")
      (when-let [actor (await (resolve-session! token))]
        (when (= "active" (:status actor)) {:actor actor :digest (secrets/token-hash token)})))))

(defn- fail!
  "Return a generic refusal without disclosing protocol codes or private state."
  [reply status]
  (.header reply "Cache-Control" "no-store")
  (.send (.code reply status) #js {:error "Shared login could not be completed"}))

(defn- html!
  "Render an origin-bound form without scripts, framing or response caching."
  [reply uid client-id actor-label token]
  (.header reply "Cache-Control" "no-store")
  (.header reply "Referrer-Policy" "no-referrer")
  (.header reply "Content-Security-Policy" "default-src 'none'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
  (.type reply "text/html; charset=utf-8")
  (.send reply (str "<!doctype html><html><head><meta charset=utf-8><title>Axxium shared login</title></head><body>"
                    "<h1>Continue with your Axxium identity</h1><p>Signed in as " (html-escape actor-label) ".</p>"
                    "<p>Allow sign-in to " (html-escape client-id) "?</p>"
                    "<form method=post action='/oidc/interaction/" (html-escape uid) "'>"
                    "<input type=hidden name=csrf value='" (html-escape token) "'>"
                    "<button name=decision value=approve>Continue</button>"
                    "<button name=decision value=deny>Cancel</button></form></body></html>")))


(defn- ^:async dispatch-engine!
  "Preserve the mount path and consume only trusted proxy protocol/host values."
  [callback issuer request reply]
  (.hijack reply)
  (let [raw (.-raw request)
        original-url (.-url raw)
        prior-original-url (.-originalUrl raw)]
    (aset (.-headers raw) "x-forwarded-proto" (.-protocol request))
    (aset (.-headers raw) "x-forwarded-host" (.-host (js/URL. issuer)))
    (set! (.-originalUrl raw) original-url)
    (set! (.-url raw) (subs original-url 5))
    (try (await (callback raw (.-raw reply)))
         (finally
           (set! (.-url raw) original-url)
           (set! (.-originalUrl raw) prior-original-url)))))

(defn ^:async install!
  "Optionally mount an operator-configured authority at the fixed /oidc issuer.
   Root session and actor resolution remain owned by Axxium, not by this UI."
  [app pool {:keys [config-file public-base-url session-cookie-name]} resolve-session! lookup-actor!]
  (when (seq config-file)
    (let [config (private-config config-file)
          issuer (:issuer config)
          origin (.-origin (js/URL. issuer))
          secure? (= "https:" (.-protocol (js/URL. issuer)))
          csrf-cookie (if secure? "__Host-axxium_oidc_csrf" "axxium_oidc_fixture_csrf")
          csrf-key (first (:cookie-keys config))]
      (when-not (= issuer (str (str/replace public-base-url #"/$" "") "/oidc"))
        (throw (ex-info "OIDC issuer must be the configured primary origin at /oidc" {})))
      (await (store/initialize! pool))
      (let [provider (engine/create-provider! pool config lookup-actor!
                       (fn [grant-id actor-id] (store/session-grant-active? pool grant-id actor-id)))
            callback (.callback provider)]
        ;; Provider receives the raw stream before Fastify can parse a token body.
        (set! (.-proxy provider) true)
        (.addHook app "onRequest"
          (fn ^:async oidc-dispatch [request reply]
            (let [url (.. request -raw -url)]
              (when (str/starts-with? url "/oidc/")
                (cond
                  (and secure? (not= "https" (.-protocol request)))
                  (fail! reply 503)

                  (not (str/starts-with? url "/oidc/interaction/"))
                  (await (dispatch-engine! callback issuer request reply)))))))
        (.addContentTypeParser app "application/x-www-form-urlencoded"
          #js {:parseAs "string" :bodyLimit 8192}
          (fn [_request body done]
            (let [form (js/URLSearchParams. body)]
              (if (and (= 1 (.-length (.getAll form "csrf")))
                       (= 1 (.-length (.getAll form "decision"))))
                (done nil #js {:csrf (.get form "csrf") :decision (.get form "decision")})
                (done (js/Error. "Invalid interaction form"))))))
        (.get app "/oidc/interaction/:uid"
          (fn ^:async show-interaction [request reply]
            (try
              (let [details (await (.interactionDetails provider (.-raw request) (.-raw reply)))
                    uid (.. request -params -uid)]
                (if-not (= uid (.-uid details))
                  (fail! reply 400)
                  (if-let [root (await (current-root request session-cookie-name resolve-session!))]
                    (if (and (= "consent" (.. details -prompt -name))
                             (not= (get-in root [:actor :id]) (.. details -session -accountId)))
                      (fail! reply 409)
                      (let [seed (.toString (.randomBytes crypto 32) "base64url")]
                        (.setCookie reply csrf-cookie seed
                          #js {:path "/" :httpOnly true :secure secure? :sameSite "strict" :maxAge 300})
                        (html! reply uid (.. details -params -client_id)
                               (or (get-in root [:actor :email]) (get-in root [:actor :display_name])
                                   (get-in root [:actor :id]))
                               (csrf-value csrf-key seed uid))))
                    (.redirect reply (str "/portal/index.html?oidc_return="
                                          (js/encodeURIComponent (str "/oidc/interaction/" uid)))))))
              (catch :default error (fail! reply (if (<= 400 (or (.-statusCode error) 503) 499) 400 503))))))
        (.post app "/oidc/interaction/:uid"
          (fn ^:async finish-interaction [request reply]
            (try
              (let [uid (.. request -params -uid)]
                (if-not (csrf-valid? request origin csrf-cookie csrf-key uid)
                  (fail! reply 403)
                  (if-let [root (await (current-root request session-cookie-name resolve-session!))]
                    (let [details (await (.interactionDetails provider (.-raw request) (.-raw reply)))
                          actor-id (get-in root [:actor :id])
                          prompt (.. details -prompt -name)
                          scopes (str/split (or (.. details -params -scope) "") #"\s+")
                          claims (vec (array-seq (or (some-> details (aget "prompt") (aget "details") (aget "missingOIDCClaims")) #js [])))
                          admission (law/interaction-admission
                                      {:expected-uid uid :actual-uid (.-uid details)
                                       :actor-id actor-id :session-actor-id (some-> details (aget "session") (aget "accountId"))
                                       :prompt prompt :decision (.. request -body -decision)
                                       :scopes scopes :claims claims})]
                      (case (:decision admission)
                        :deny
                        (do (.hijack reply)
                            (await (.interactionFinished provider (.-raw request) (.-raw reply)
                                     #js {:error "access_denied" :error_description "User declined sign-in"}
                                     #js {:mergeWithLastSubmission false})))
                        :login
                        (do (.hijack reply)
                            (await (.interactionFinished provider (.-raw request) (.-raw reply)
                                     #js {:login #js {:accountId actor-id}}
                                     #js {:mergeWithLastSubmission false})))
                        :consent
                        (let [grant (new (.-Grant provider)
                                         #js {:accountId actor-id :clientId (.. details -params -client_id)})]
                          (.addOIDCScope grant (str/join " " scopes))
                          (.addOIDCClaims grant (clj->js claims))
                          (let [grant-id (await (.save grant))]
                            (await (store/bind-session! pool grant-id actor-id (:digest root)))
                            (.hijack reply)
                            (await (.interactionFinished provider (.-raw request) (.-raw reply)
                                     #js {:consent #js {:grantId grant-id}}
                                     #js {:mergeWithLastSubmission false}))))
                        (fail! reply (:status admission))))
                    (fail! reply 401))))
              (catch :default error
                (if (.-sent reply)
                  (do (when-not (.. reply -raw -headersSent)
                        (set! (.. reply -raw -statusCode) 503)
                        (.setHeader (.-raw reply) "Cache-Control" "no-store"))
                      (.end (.-raw reply) "Shared login could not be completed"))
                  (fail! reply (if (<= 400 (or (.-statusCode error) 503) 499) 400 503)))))))
        true))))
