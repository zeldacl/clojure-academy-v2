(ns cn.li.forge1201.runtime.sync
  "Forge thin adapter for server-side sync scheduler.
  Delegates all dirty-set and flush logic to mc1201 sync-core."
  (:require [cn.li.mc1201.runtime.sync-core :as sync-core]))

(defn mark-player-dirty! [uuid]
  (sync-core/mark-player-dirty! uuid))

(defn mark-all-dirty! []
  (sync-core/mark-all-dirty!))

(defn tick-sync!
  "Flush dirty player snapshots periodically.
  send-fn: (fn [uuid payload]) supplied by network bridge."
  [send-fn]
  (sync-core/tick-sync! send-fn))
