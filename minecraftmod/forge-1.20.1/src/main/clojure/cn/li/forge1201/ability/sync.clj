(ns cn.li.forge1201.ability.sync
  "Server-side sync scheduler for ability data.

  Current strategy:
  - mark-player-dirty! is called by lifecycle/tick or request handlers
  - tick-sync! flushes at fixed interval and logs sync payload size

  Transport integration now uses ability-network/send-sync-to-client!.
  This namespace keeps the dirty-set and flush cadence centralized."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private dirty-players (atom #{}))
(defonce ^:private tick-counter (atom 0))
(def ^:private flush-interval-ticks 10)

(defn mark-player-dirty! [uuid]
  (swap! dirty-players conj uuid))

(defn mark-all-dirty! []
  (reset! dirty-players (set (keys @ps/player-states))))

(defn- build-sync-payload [uuid]
  (let [state (ps/get-player-state uuid)]
    {:uuid uuid
     :ability-data (:ability-data state)
     :resource-data (:resource-data state)
     :cooldown-data (:cooldown-data state)
     :preset-data (:preset-data state)}))

(defn tick-sync!
  "Flush dirty player snapshots periodically.
  send-fn: (fn [uuid payload]) supplied by network bridge."
  [send-fn]
  (let [t (swap! tick-counter inc)]
    (when (zero? (mod t flush-interval-ticks))
      (doseq [uuid @dirty-players]
        (when-let [state (ps/get-player-state uuid)]
          (when send-fn
            (send-fn uuid (build-sync-payload uuid)))
          (ps/mark-clean! uuid)))
      (reset! dirty-players #{}))))
