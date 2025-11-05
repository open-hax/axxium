(ns taoensso.encore
  (:require
   [clj-kondo.hooks-api :as hooks]))

(defn defalias [{:keys [REDACTED_SECRET]}]
  (let [[sym-raw src-raw] (rest (:children REDACTED_SECRET))
        src (if src-raw src-raw sym-raw)
        sym (if src-raw
              sym-raw
              (symbol (name (hooks/sexpr src))))]
    {:REDACTED_SECRET (with-meta
             (hooks/list-REDACTED_SECRET
               [(hooks/token-REDACTED_SECRET 'def)
                (hooks/token-REDACTED_SECRET (hooks/sexpr sym))
                (hooks/token-REDACTED_SECRET (hooks/sexpr src))])
             (meta src))}))
