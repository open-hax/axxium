(ns axxium.law.token
  "Admission laws for actor tokens.

   Objects. Segment is the set of audience components (no plus, length 1–63).
   Issuer is the set of instance identifiers (no whitespace, length 1–200).
   An instance ι is (iss, service, env) with iss ∈ Issuer and service, env ∈ Segment.
   audience(service, env) = service + \"+\" + env.

   Laws.
   - Env/aud matching. token-env-matches-aud?(aud, env) holds exactly when
     env ∈ Segment and there is a unique service ∈ Segment with
     aud = audience(service, env).
   - Issuer. iss-admitted?(i, ι) holds exactly when i = ι and i ∈ Issuer.
   - Token validity. valid-token-claims?(c, ι) holds exactly when ι is an
     admitted instance, iss-admitted?(c.iss, ι.iss), token-env-matches-aud?(c.aud, c.env),
     c.env = ι.env, c.aud = audience(ι.service, ι.env), c.cred_id is a credential id,
     and c.sub is a non-empty string.
   - Credential projection. (:auth/cred-id (auth-context actor cred-id)) = cred-id
     and (:cred_id (actor-claims actor cred-id ι)) = cred-id."
  (:require [axxium.domain.token :as domain]
            [axxium.law.actor :as actor]
            [clojure.string :as str]))

(def ^:private segment-pattern #"[A-Za-z0-9][A-Za-z0-9._-]*")

(defn segment?
  "An audience component: 1–63 characters and no plus, so service+env splits once."
  [value]
  (and (string? value)
       (<= 1 (count value) 63)
       (= value (re-matches segment-pattern value))))

(defn issuer?
  "An instance identifier: 1–200 characters on one line, without whitespace."
  [value]
  (and (string? value)
       (<= 1 (count value) 200)
       (not (re-find #"\s" value))))

(defn admitted-instance?
  "ι = (iss, service, env) is admitted when iss is an issuer and both audience parts are segments."
  [{:keys [iss service env]}]
  (and (issuer? iss)
       (segment? service)
       (segment? env)))

(defn token-env-matches-aud?
  "Env/aud matching law.
   aud matches env when env is a segment and aud is service+env for exactly one segment service."
  [aud env]
  (and (segment? env)
       (string? aud)
       (let [suffix (str "+" env)
             marker (str/last-index-of aud suffix)]
         (and (some? marker)
              (= marker (- (count aud) (count suffix)))
              (segment? (subs aud 0 marker))))))

(defn iss-admitted?
  "Issuer law: the token issuer equals this instance's issuer, and that issuer is admissible."
  [token-iss instance-iss]
  (and (issuer? instance-iss)
       (= token-iss instance-iss)))

(defn configured-audience-admitted?
  "A JWT_AUDIENCE override is admitted only when it is audience(service, env).
   A missing override is admitted."
  [configured-aud service env]
  (or (nil? configured-aud)
      (and (segment? service)
           (segment? env)
           (= configured-aud (domain/audience service env)))))

(defn valid-token-claims?
  "Token-validity law for claims c against instance ι.
   Rejects a wrong issuer and an env that does not match aud."
  [claims {:keys [iss service env] :as instance}]
  (and (admitted-instance? instance)
       (iss-admitted? (:iss claims) iss)
       (token-env-matches-aud? (:aud claims) (:env claims))
       (= (:env claims) env)
       (= (:aud claims) (domain/audience service env))
       (actor/valid-credential-id? (:cred_id claims))
       (string? (:sub claims))
       (pos? (count (:sub claims)))))
