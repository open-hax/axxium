(ns axxium.extern.oidc-fixture
  "Native integration-test entry point; never mounted as an application route."
  (:require [axxium.extern.oidc-provider :as provider]
            [axxium.extern.oidc-http :as transport]
            [axxium.extern.oidc-store :as store]))

(defn create-provider
  "Decode synthetic native fixture data around the ordinary provider boundary."
  [pool native-config native-lookup]
  (provider/create-provider!
    pool (js->clj native-config :keywordize-keys true)
    (fn ^:async lookup-fixture [subject]
      (some-> (await (native-lookup subject)) (js->clj :keywordize-keys true)))
    (fn [grant-id subject] (store/session-grant-active? pool grant-id subject))
    nil))

(defn install-http
  "Decode native fixture options around the ordinary optional HTTP transport."
  [app pool options native-session native-lookup]
  (transport/install! app pool (js->clj options :keywordize-keys true)
    (fn ^:async session-fixture [token]
      (some-> (await (native-session token)) (js->clj :keywordize-keys true)))
    (fn ^:async actor-fixture [subject]
      (some-> (await (native-lookup subject)) (js->clj :keywordize-keys true)))))

(defn bind-mailbox
  "Bind a synthetic grant using the production PostgreSQL path."
  [pool grant-id account-id credential digest did client resource actor-did]
  (store/bind-mailbox-session! pool grant-id
    {:principal {:auth/did did :auth/method :atproto}
     :actor-id account-id :cred-id credential :token-hash digest}
    client resource actor-did))

(defn ^:async mailbox-grant-active?
  "Report authorization without exposing the fixture's proof."
  [pool grant-id account-id client resource actor-did]
  (some? (await (store/mailbox-grant-proof! pool grant-id account-id client resource actor-did))))

(defn ^:async install-owner-http
  "Mount the real authority over a synthetic human browser session and actor tree."
  [app pool options native-session native-lookup native-human-session authorized?]
  (let [opts (update (js->clj options :keywordize-keys true) :mailbox-owner-authority
               assoc :resolve-human-session!
               (fn ^:async human-fixture [token]
                 (some-> (await (native-human-session token)) (js->clj :keywordize-keys true)))
               :authorized? authorized?)
        oauth (await
                (transport/install! app pool opts
                  (fn ^:async session-fixture [token]
                    (some-> (await (native-session token)) (js->clj :keywordize-keys true)))
                  (fn ^:async actor-fixture [subject]
                    (some-> (await (native-lookup subject)) (js->clj :keywordize-keys true)))))]
    #js {:accessProof
         (fn ^:async access-proof [token]
           (let [proof (await ((:access-proof! oauth) token))]
             (when proof
               #js {:subject (:actor-id proof)
                    :workAccount (:mailbox/principal-actor-id proof)
                    :mailboxDid (get-in proof [:principal :auth/did])
                    :authMethod (name (get-in proof [:principal :auth/method]))
                    :boundActorDid (:mailbox/bound-actor-did proof)})))}))
