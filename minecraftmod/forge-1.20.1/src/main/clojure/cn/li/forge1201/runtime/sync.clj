(ns cn.li.forge1201.runtime.sync
  "Server-side sync scheduler for ability data.

  Current strategy:
  - mark-player-dirty! is called by lifecycle/tick or request handlers
  - tick-sync! flushes at fixed interval and logs sync payload size

  Transport integration now uses ability-network/send-sync-to-client!.
  This namespace keeps the dirty-set and flush cadence centralized."
  (:require [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private dirty-players (atom #{}))
(defonce ^:private tick-counter (atom 0))
(def ^:private flush-interval-ticks 10)

(defn mark-player-dirty! [uuid]
  (swap! dirty-players conj uuid))

(defn mark-all-dirty! []
  (reset! dirty-players (set (ability-runtime/list-player-uuids))))

(defn- build-sync-payload [uuid]
  (ability-runtime/build-sync-payload uuid))

(defn tick-sync!
  "Flush dirty player snapshots periodically.
  send-fn: (fn [uuid payload]) supplied by network bridge."
  [send-fn]
  (let [t (swap! tick-counter inc)]
    (when (zero? (mod t flush-interval-ticks))
      (doseq [uuid @dirty-players]
        (when-let [payload (build-sync-payload uuid)]
          (when send-fn
            (send-fn uuid payload))
          (ability-runtime/mark-player-clean! uuid)))
      (reset! dirty-players #{}))))
