(ns axxium.extern.relay
  "TLS/JOSE adapter for machine leases signed by the persistent OIDC key."
  (:require ["jose" :as jose]
            ["node:crypto" :as crypto]
            [axxium.law.oidc :as oidc]
            [axxium.law.relay :as law]))

(defn- digest [value]
  (.digest (.update (.createHash crypto "sha256") value) "hex"))

(defn- secret-equal? [expected supplied]
  ;; Fixed-length digests avoid secret-length timing differences.
  (.timingSafeEqual crypto (js/Buffer.from (digest expected) "hex")
                          (js/Buffer.from (digest supplied) "hex")))

(defn- require-audiences! [config]
  (doseq [grant (:node-grants config)]
    (let [value (:broker-audience grant)
          url (js/URL. value)]
      (when-not (and (= "https:" (.-protocol url)) (= value (.-origin url)))
        (throw (ex-info "Relay audience must be an exact HTTPS broker origin" {})))))
  config)

(defn ^:async install!
  "Import the startup-validated private key once; reread current grants on EVERY
   request. Changes to issuer/key/browser configuration require restart and refuse
   issuance until then. No request may select owner, audience, scope or signing key."
  [app startup load-current! lookup-actor!]
  (require-audiences! startup)
  (let [jwk (first (get-in startup [:jwks :keys]))
        signing-key (await (jose/importJWK (clj->js jwk) "RS256"))]
    (.post app law/endpoint #js {:bodyLimit 2048
                                  :errorHandler (fn [_error _request reply]
                                                  ;; Fastify parser errors occur before the handler. Do not
                                                  ;; log or serialize the parser error or submitted input.
                                                  (.header reply "Cache-Control" "no-store")
                                                  (.send (.code reply 403) #js {:error "Relay authorization refused"}))}
      (fn ^:async issue [request reply]
        (.header reply "Cache-Control" "no-store")
        (try
          (let [config (-> (load-current!) oidc/require-config! require-audiences!)
                body (js->clj (.-body request) :keywordize-keys true)
                node (:node-id body) supplied (:secret body)
                grant (some #(when (= node (:node-id %)) %) (:node-grants config))
                now (js/Math.floor (/ (.now js/Date) 1000))]
            (when-not (and (= "https" (.-protocol request))
                           (= (dissoc startup :node-grants) (dissoc config :node-grants))
                           (= #{:node-id :secret} (set (keys body)))
                           (string? node) (string? supplied) (<= 32 (count supplied) 512)
                           (secret-equal? (or (:secret grant) "unregistered-node") supplied)
                           (law/issuance-admitted? grant (await (lookup-actor! (:owner-subject grant))) now))
              (throw (ex-info "Relay authorization refused" {})))
            (let [claims (law/claims (:issuer config) grant now (.randomUUID crypto)
                                    (digest (pr-str (into (sorted-map) (dissoc grant :secret)))))
                  jwt (await (-> (jose/SignJWT. (clj->js claims))
                                 (.setProtectedHeader #js {:alg "RS256" :typ "axxium-relay+jwt" :kid (:kid jwk)})
                                 (.sign signing-key)))]
              (.send reply #js {:lease jwt :expires_at (:exp claims)})))
          (catch :default _
            ;; Do not surface config/parser/DB/JOSE errors or submitted credentials.
            (.send (.code reply 403) #js {:error "Relay authorization refused"})))))))
