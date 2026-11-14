(ns axxium.extern.ledger-queue
  "Serialize access to a single-writer ledger and reload after uncertain writes."
  (:require [axxium.extern.ledger-file :as disk]))

(defn create
  "Create one writer with optional descriptor-safe storage operations."
  ([] (create {}))
  ([storage]
   {:ledger (atom nil) :queue (atom (js/Promise.resolve nil)) :storage storage}))

(defn current-ledger
  "Return the cached ledger, reloading it after an uncertain write."
  [{:keys [ledger storage]} path]
  (or @ledger (reset! ledger ((get storage :load! disk/load-ledger) path))))

(defn submit!
  "Run f exclusively and persist its resulting ledger before returning it."
  [{:keys [ledger queue storage] :as writer} path f]
  (let [run (.then @queue
                   (fn [_]
                     (let [before (current-ledger writer path)]
                       (.then (js/Promise.resolve (f before))
                              (fn [out]
                                (when-let [after (:ledger out)]
                                  ;; A failed append may still have written complete lines.
                                  ;; Invalidate first so the next operation reloads disk.
                                  (reset! ledger nil)
                                  (reset! ledger ((get storage :append! disk/append-batch!) path before after)))
                                out)))))]
    (reset! queue (.catch run (fn [_] nil)))
    run))

(defn submit-checkpointed!
  "Run f exclusively with a durable checkpoint for an external effect.
   f receives the current ledger and a synchronous `checkpoint!` function.
   Final state still persists through submit!. The checkpoint cannot be used
   after f completes and never admits a different ledger prefix."
  [{:keys [ledger storage] :as writer} path f]
  (submit! writer path
           (fn ^:async with-checkpoints [before]
             (let [current (volatile! before)
                   active? (volatile! true)
                   checkpoint! (fn [after]
                                 (let [prefix @current]
                                   (when-not (and @active? (vector? after)
                                                  (< (count prefix) (count after))
                                                  (= prefix (subvec after 0 (count prefix))))
                                     (throw (js/Error. "Unsafe ledger checkpoint")))
                                   (reset! ledger nil)
                                   (let [committed ((get storage :append! disk/append-batch!) path prefix after)]
                                     (vreset! current committed)
                                     (reset! ledger committed))))]
               (try
                 (let [{after :ledger :as out} (await (f before checkpoint!))]
                   (when (and after (not= @current after)) (checkpoint! after))
                   (dissoc out :ledger))
                 (finally (vreset! active? false)))))))
