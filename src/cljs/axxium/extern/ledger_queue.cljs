(ns axxium.extern.ledger-queue
  "Serialize access to a single-writer ledger and reload after uncertain writes."
  (:require [axxium.extern.ledger-file :as disk]))

(defn create []
  {:ledger (atom nil) :queue (atom (js/Promise.resolve nil))})

(defn submit!
  "Run f exclusively and persist its resulting ledger before returning it."
  [{:keys [ledger queue]} path f]
  (let [run (.then @queue
                   (fn [_]
                     (let [before (or @ledger (reset! ledger (disk/load-ledger path)))]
                       (.then (js/Promise.resolve (f before))
                              (fn [out]
                                (when-let [after (:ledger out)]
                                  ;; A failed append may still have written complete lines.
                                  ;; Invalidate first so the next operation reloads disk.
                                  (reset! ledger nil)
                                  (reset! ledger (disk/append-batch! path before after)))
                                out)))))]
    (reset! queue (.catch run (fn [_] nil)))
    run))
