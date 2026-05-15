(ns cn.li.forge1201.client.runtime-bridge
  "CLIENT-ONLY Forge adapter for runtime hooks."
  (:require [cn.li.mc1201.client.effects.particle :as particle]
            [cn.li.mc1201.client.effects.sound :as sound]
            [cn.li.forge1201.client.key-input :as key-input]
            [cn.li.mc1201.client.overlay.state :as overlay-state]
            [cn.li.mcmod.hooks.core :as power-runtime]
             [cn.li.mcmod.util.log :as log]
             [cn.li.mc1201.client.player-state-core :as player-state])
  (:import [cn.li.forge1201.client.effect IntensifyEffectSpawner]
            [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.eventbus.api EventPriority]))

(defonce ^:private tick-listener-registered? (atom false))

(defn active-contexts []
  (power-runtime/client-active-contexts))

(defn latest-sync [player-uuid]
  (power-runtime/client-latest-sync player-uuid))

(defn local-player-item-id []
  (player-state/local-player-item-id))

(defn local-player-pos [] (player-state/local-player-pos))
(defn local-player-eye-pos [] (player-state/local-player-eye-pos))
(defn local-player-look-end [distance] (player-state/local-player-look-end distance))

(defn clear-client-activated-overlay! []
  (overlay-state/clear-client-activated!))

(defn play-intensify-local-effect! []
  (IntensifyEffectSpawner/spawnLocal))

(defn notify-charge-coin-throw-client! [player-uuid]
  (power-runtime/client-notify-charge-coin-throw! player-uuid))

(defn notify-railgun-coin-throw-client! [player-uuid]
  (notify-charge-coin-throw-client! player-uuid))

(defn charge-coin-visual-state [player-uuid]
  (power-runtime/client-charge-coin-visual-state player-uuid))

(defn railgun-charge-visual-state [player-uuid]
  (charge-coin-visual-state player-uuid))

(defn slot-visual-state [player-uuid key-idx]
  (power-runtime/client-slot-visual-state player-uuid key-idx))

(defn body-intensify-charge-visual-state []
  (power-runtime/client-body-intensify-charge-visual-state))

(defn current-charging-visual-state []
  (power-runtime/client-current-charging-visual-state))

(defn on-slot-key-down! [player-uuid key-idx]
  (power-runtime/client-on-slot-key-down! player-uuid key-idx))

(defn on-slot-key-tick! [player-uuid key-idx]
  (power-runtime/client-on-slot-key-tick! player-uuid key-idx))

(defn on-slot-key-up! [player-uuid key-idx]
  (power-runtime/client-on-slot-key-up! player-uuid key-idx))

(defn abort-all! []
  (power-runtime/client-abort-all!))

(defn tick-client! []
  (key-input/tick-input!)
  (particle/tick-particles!)
  (sound/tick-sounds!)
  (power-runtime/client-tick!))

(defn- on-client-tick [^TickEvent$ClientTickEvent evt]
  (when (= TickEvent$Phase/END (.phase evt))
    (tick-client!)))

(defn init! []
  (power-runtime/client-register-push-handlers!)
  (when (compare-and-set! tick-listener-registered? false true)
    (.addListener (MinecraftForge/EVENT_BUS)
                  EventPriority/NORMAL false TickEvent$ClientTickEvent
                  (reify java.util.function.Consumer
                    (accept [_ evt] (on-client-tick evt)))))
  (log/info "Client runtime bridge initialized"))