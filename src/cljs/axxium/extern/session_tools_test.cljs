(ns axxium.extern.session-tools-test
  "An agent opens its own session through the identity tools; the session
   authenticates with its own key, self-advertises, and is found."
  (:require [axxium.domain.github-identity :as gi]
            [axxium.domain.policy :as policy]
            [axxium.domain.state :as state]
            [axxium.extern.github-identity-mcp :as mcp]
            [cljs.test :refer [async deftest is]]))

(def ^:private root "did:web:axxium.example:actors:owner")
(def ^:private codex (str root ":codex"))
(def ^:private grokbot (str root ":grokbot"))
(def ^:private pol {:policy/repos #{"fixture/a"} :policy/permissions {"contents" "read"}
                    :policy/actions #{:read} :policy/forbid #{}})

(defn- tree []
  (let [lg (:ledger (gi/register-root [] state/empty-state root "Owner" pol (mcp/event-ids)))
        lg (:ledger (gi/provision-actor lg (state/fold lg) root codex "Codex" pol :agent (mcp/event-ids)))]
    (:ledger (gi/provision-actor lg (state/fold lg) root grokbot "Grok Bot" pol :agent (mcp/event-ids)))))

(defn- ^:async session-tools* []
  (let [call (fn [lg actor tool args] (mcp/call {:ledger lg :ids mcp/event-ids} {:actor actor} tool args))
        opened (await (call (tree) codex "session_open" {:actor/name "PR 66 review" :session/lease-ms 600000
                                                         :session/advertise? false}))
        {session :actor/did key :bootstrap/key} (:result opened)
        lg (:ledger opened)]
    (is (= (str codex ":") (subs session 0 (inc (count codex)))))
    (is (= session (mcp/authenticate (state/fold lg) session key)) "S1: the session holds its own key")
    (is (nil? (mcp/authenticate (state/fold lg) codex key)) "it is not the parent's key")
    (is (policy/decision-before-effect? lg))
    (let [long-lease (await (call lg codex "session_open" {:session/lease-ms 172800000}))]
      (is (<= (- (inst-ms (get-in long-lease [:result :actor/lease-until])) (js/Date.now)) 86400000)
          "a requested lease beyond a day is clamped to a day"))
    (let [advertised (await (call lg session "actor_advertise" {:advert/anchor root :advert/tags ["review" "pr-66"]}))
          lg (:ledger advertised)
          found (await (call lg grokbot "actor_find" {:advert/anchor root :query "review"}))]
      (is (some? (:result advertised)))
      (is (= [session] (mapv :actor/did (get-in found [:result :actors]))))
      (let [closed (await (call lg codex "session_close" {:actor/did session}))]
        (is (some? (:result closed)))
        (is (nil? (mcp/authenticate (state/fold (:ledger closed)) session key)) "closing revokes the session")
        (is (= [] (get-in (await (call (:ledger closed) grokbot "actor_find" {:advert/anchor root}))
                          [:result :actors])))
        (is (= :forbidden (:error (await (call lg codex "session_close" {:actor/did grokbot}))))
            "session_close only closes sessions")))))

(defn- ^:async session-defaults* []
  (let [call (fn [lg actor tool args] (mcp/call {:ledger lg :ids mcp/event-ids} {:actor actor} tool args))
        lg (tree)
        opened (await (call lg codex "session_open" {:advert/tags ["review"]}))
        session (get-in opened [:result :actor/did])]
    (is (= codex (get-in opened [:result :advert/anchor])) "S2: a new session advertises at its parent")
    (is (= [session] (mapv :actor/did (gi/directory-at (state/fold (:ledger opened)) codex (js/Date.)))))
    (is (= [session] (mapv :actor/did (get-in (await (call (:ledger opened) grokbot "actor_find"
                                                            {:advert/anchor codex :query "review"}))
                                              [:result :actors]))))
    (is (= :invalid (:error (await (call lg codex "session_open" {:actor/policy {:policy/repos "*"}}))))
        "a malformed policy is refused at the boundary")
    (is (= :invalid (:error (await (call lg codex "session_open" {:advert/tags "review"})))))
    (is (= :forbidden (:error (await (call lg codex "session_open"
                                           {:actor/policy (assoc pol :policy/repos #{"*"})}))))
        "G1: a wider policy is refused")))

(defn- ^:async refused-advert* []
  (let [opened (with-redefs [gi/advertise (fn [lg & _] {:ledger lg})]
                 (await (mcp/call {:ledger (tree) :ids mcp/event-ids} {:actor codex}
                                  "session_open" {:advert/tags ["review"]})))
        {session :actor/did key :bootstrap/key :as result} (:result opened)
        st (state/fold (:ledger opened))]
    (is (nil? (:error opened)) "a refused default advert does not fail the open")
    (is (string? key) "the issued key is still returned")
    (is (= :forbidden (:advert/error result)) "the advert failure is reported")
    (is (nil? (:advert/anchor result)))
    (is (gi/live? st session (js/Date.)) "the session is written and live")
    (is (empty? (gi/directory-at st codex (js/Date.))) "nothing was advertised")
    (is (= session (mcp/authenticate st session key)) "the session authenticates with the returned key")))

(deftest a-refused-default-advert-still-returns-a-usable-session
  (async done
    (-> (refused-advert*)
        (.catch (fn [e] (is false (str "refused-advert rejected: " (pr-str e)))))
        (.finally done))))

(deftest sessions-advertise-at-their-parent-by-default
  (async done
    (-> (session-defaults*)
        (.catch (fn [e] (is false (str "session-defaults rejected: " (pr-str e)))))
        (.finally done))))

(deftest agents-open-advertise-and-close-their-own-sessions
  (async done
    (-> (session-tools*)
        (.catch (fn [e] (is false (str "session-tools rejected: " (pr-str e)))))
        (.finally done))))
