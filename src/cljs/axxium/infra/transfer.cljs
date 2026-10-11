(ns axxium.infra.transfer
  "Signed, expiring identity transfer between explicitly pinned Axxium issuers."
  (:require [axxium.config :as cfg]
            [axxium.domain.identity :as identity]
            [axxium.extern.jose :as jose]
            [axxium.extern.runtime :as runtime]
            [axxium.law.identity :as law]))
(defn- local-key [] (runtime/json-file (cfg/get-in-config [:identity/private-key-file])))
(defn- trust [] (or (runtime/json-file (cfg/get-in-config [:identity/trust-file])) {}))
(defn public-key "Public verification key only; the private scalar never leaves the host." []
  (select-keys (local-key) [:kty :crv :x :kid]))
(defn trusted-recipients "Configured destinations available to the signed-in user." []
  (mapv #(subs (str %) 1) (keys (trust))))
(defn ^:async export! "Bind a transfer to one configured receiving instance for five minutes." [actor recipient]
  (when-not (get (trust) (keyword recipient))
    (throw (ex-info "Recipient is not configured as a trusted instance" {:status 400 :code "unknown_recipient"})))
  (when-not (local-key)
    (throw (ex-info "Identity transfer signing is not configured" {:status 503 :code "not_configured"})))
  (await (jose/sign-transfer (identity/transfer-payload actor (str (random-uuid)))
                            (local-key) (cfg/get-in-config [:axxium/public-base-url]) recipient)))
(defn ^:async verify! "Accept only a pinned signer, local audience and validated identity claims." [value]
  (try
    (when-not (and (string? value) (< (count value) 16384))
      (throw (ex-info "Invalid transfer" {})))
    (let [issuer (jose/unverified-issuer value)
          key (get (trust) (keyword issuer))]
      (when-not key (throw (ex-info "Unknown issuer" {})))
      (let [claims (await (jose/verify-transfer value key issuer (cfg/get-in-config [:axxium/public-base-url])))]
        (law/require-transfer! claims (runtime/now-seconds))))
    (catch :default _
      (throw (ex-info "Identity transfer is invalid, expired, or from an untrusted issuer"
                      {:status 401 :code "invalid_transfer"})))))
