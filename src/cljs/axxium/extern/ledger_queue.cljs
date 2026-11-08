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

