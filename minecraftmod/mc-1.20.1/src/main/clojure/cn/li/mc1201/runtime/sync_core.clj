(ns cn.li.mc1201.runtime.sync-core
  "Loader-agnostic dirty-player tracking and sync flush scheduling.

  No Minecraft or loader imports — pure Clojure state management.
  Platform adapters supply the send-fn transport when calling tick-sync!."
  (:require [cn.li.mcmod.hooks.core :as power-runtime]))

(defonce ^:private dirty-players (atom #{}))
(defonce ^:private tick-counter (atom 0))
(def ^:private flush-interval-ticks 10)

(defn mark-player-dirty! [uuid]
  (swap! dirty-players conj uuid))

(defn mark-all-dirty! []
  (reset! dirty-players (set (power-runtime/list-player-uuids))))

(defn- build-sync-payload [uuid]
  (power-runtime/build-sync-payload uuid))

(defn tick-sync!
  "Flush dirty player snapshots periodically.
  send-fn: (fn [uuid payload]) — supplied by the platform network bridge."
  [send-fn]
  (let [t (swap! tick-counter inc)]
    (when (zero? (mod t flush-interval-ticks))
      (doseq [uuid @dirty-players]
        (when-let [payload (build-sync-payload uuid)]
          (when send-fn
            (send-fn uuid payload))
          (power-runtime/mark-player-clean! uuid)))
      (reset! dirty-players #{}))))
