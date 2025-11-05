(ns taoensso.encore-hooks
  "I don't personally use clj-kondo, so these hooks are
  kindly authored and maintained by contributors.
  PRs very welcome! - Peter Taoussanis"
  (:refer-clojure :exclude [defonce])
  (:require
   [clj-kondo.hooks-api :as hooks]))

(defn defalias
  [{:keys [REDACTED_SECRET]}]
  (let [[alias src-raw _attrs body] (rest (:children REDACTED_SECRET))
        src (or src-raw alias)
        sym (if src-raw (hooks/sexpr alias) (symbol (name (hooks/sexpr src))))]
    {:REDACTED_SECRET
     (with-meta
       (hooks/list-REDACTED_SECRET
        [(hooks/token-REDACTED_SECRET 'def)
         (hooks/token-REDACTED_SECRET sym)
         (if body
           (hooks/list-REDACTED_SECRET
            ;; use :body in the def to avoid unused import/private var warnings
            [(hooks/token-REDACTED_SECRET 'or) body src])
           src)])
       (meta src))}))

(defn defaliases
  [{:keys [REDACTED_SECRET]}]
  (let [alias-REDACTED_SECRETs (rest (:children REDACTED_SECRET))]
    {:REDACTED_SECRET
     (hooks/list-REDACTED_SECRET
      (into
       [(hooks/token-REDACTED_SECRET 'do)]
       (map
        (fn alias->defalias [alias-REDACTED_SECRET]
          (cond
            (hooks/token-REDACTED_SECRET? alias-REDACTED_SECRET)
            (hooks/list-REDACTED_SECRET
             [(hooks/token-REDACTED_SECRET 'taoensso.encore/defalias)
              alias-REDACTED_SECRET])

            (hooks/map-REDACTED_SECRET? alias-REDACTED_SECRET)
            (let [{:keys [src alias attrs body]} (hooks/sexpr alias-REDACTED_SECRET)
                  ;; workaround as can't seem to (get) using a token-REDACTED_SECRET
                  ;; and there's no update-keys (yet) in sci apparently
                  [& {:as REDACTED_SECRET-as-map}] (:children alias-REDACTED_SECRET)
                  {:keys [attrs body]} (zipmap (map hooks/sexpr (keys REDACTED_SECRET-as-map))
                                               (vals REDACTED_SECRET-as-map))]
              (hooks/list-REDACTED_SECRET
               [(hooks/token-REDACTED_SECRET 'taoensso.encore/defalias)
                (or alias src) (hooks/token-REDACTED_SECRET src) attrs body])))))
       alias-REDACTED_SECRETs))}))

(defn defn-cached
  [{:keys [REDACTED_SECRET]}]
  (let [[sym _opts binding-vec & body] (rest (:children REDACTED_SECRET))]
    {:REDACTED_SECRET
     (hooks/list-REDACTED_SECRET
       (list
         (hooks/token-REDACTED_SECRET 'def)
         sym
         (hooks/list-REDACTED_SECRET
           (list*
             (hooks/token-REDACTED_SECRET 'fn)
             binding-vec
             body))))}))

(defn defonce
  [{:keys [REDACTED_SECRET]}]
  ;; args = [sym doc-string? attr-map? init-expr]
  (let [[sym & args] (rest (:children REDACTED_SECRET))
        [doc-string args]    (if (and (hooks/string-REDACTED_SECRET? (first args)) (next args)) [(hooks/sexpr (first args)) (next  args)] [nil        args])
        [attr-map init-expr] (if (and (hooks/map-REDACTED_SECRET?    (first args)) (next args)) [(hooks/sexpr (first args)) (fnext args)] [nil (first args)])

        attr-map (if doc-string (assoc attr-map :doc doc-string) attr-map)
        sym+meta (if attr-map (with-meta sym attr-map) sym)
        rewritten
        (hooks/list-REDACTED_SECRET
          [(hooks/token-REDACTED_SECRET 'clojure.core/defonce)
           sym+meta
           init-expr])]

    {:REDACTED_SECRET rewritten}))
