(ns axxium.domain.token
  "Claim construction for an actor acting through one credential on one instance.

   Objects. An instance is a triple (iss, service, env). A credential identifier
   names the session row or the agent credential that acted. Claims are the
   image of (actor, cred-id, instance).

   Morphism. audience : Segment × Segment → String sends (service, env) to
   service + \"+\" + env. The token claim aud is that string, env is the
   instance environment, and cred_id is the acting credential.")

(defn audience
  "The service+env audience: service and env joined by one plus."
  [service env]
  (str service "+" env))

(defn actor-claims
  "Claims an actor token carries for cred-id on instance."
  [actor cred-id {:keys [iss service env]}]
  {:sub (:id actor)
   :entity-id (:entity_id actor)
   :email (:email actor)
   :capabilities (:capabilities actor)
   :roles (:roles actor)
   :status (:status actor)
   :cred_id cred-id
   :iss iss
   :aud (audience service env)
   :env env})

(defn auth-context
  "In-process auth context for an actor acting through cred-id.
   The JWT claim is cred_id; the context key is :auth/cred-id."
  [actor cred-id]
  {:auth/actor-id (:id actor)
   :auth/entity-id (:entity_id actor)
   :auth/email (:email actor)
   :auth/capabilities (:capabilities actor)
   :auth/roles (:roles actor)
   :auth/cred-id cred-id})
