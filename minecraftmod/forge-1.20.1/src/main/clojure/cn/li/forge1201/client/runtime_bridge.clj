(ns cn.li.forge1201.client.runtime-bridge
  "CLIENT-ONLY Forge adapter for runtime hooks."
  (:require [cn.li.mc1201.client.effects.particle :as particle]
            [cn.li.mc1201.client.effects.sound :as sound]
            [cn.li.mc1201.client.session-cleanup :as session-cleanup]
            [cn.li.mc1201.client.session :as client-session]
            [cn.li.forge1201.client.key-input :as key-input]
            [cn.li.mc1201.client.overlay.state :as overlay-state]
            [cn.li.mcmod.hooks.core :as power-runtime]
             [cn.li.mcmod.util.log :as log]
             [cn.li.mc1201.client.player-state-core :as player-state])
  (:import [cn.li.mc1201.client.effect ScriptedEffectSpawner]
            [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.eventbus.api EventPriority]))

(def ^:private tick-listener-guard-lock
  (Object.))

(def ^:private ^:dynamic *tick-listener-registered?*
  false)

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
  (if-let [owner (client-session/current-local-player-owner)]
    (overlay-state/clear-client-activated! owner)
    (when-let [session-id (client-session/client-session-id)]
      (overlay-state/clear-client-overlay-session! session-id))))

(defn spawn-local-scripted-effect! [effect-id]
  (ScriptedEffectSpawner/spawnLocal effect-id))

(defn slot-visual-state [player-uuid key-idx]
  (client-session/with-current-client-session #(power-runtime/client-slot-visual-state player-uuid key-idx)))

(defn on-slot-key-down! [player-uuid key-idx]
  (client-session/with-current-client-session #(power-runtime/client-on-slot-key-down! player-uuid key-idx)))

(defn on-slot-key-tick! [player-uuid key-idx]
  (client-session/with-current-client-session #(power-runtime/client-on-slot-key-tick! player-uuid key-idx)))

(defn on-slot-key-up! [player-uuid key-idx]
  (client-session/with-current-client-session #(power-runtime/client-on-slot-key-up! player-uuid key-idx)))

(defn on-slot-key-abort! [player-uuid key-idx]
  (client-session/with-current-client-session #(power-runtime/client-on-slot-key-abort! player-uuid key-idx)))

(defn on-movement-key-down! [player-uuid movement-key]
  (client-session/with-current-client-session #(power-runtime/client-on-movement-key-down! player-uuid movement-key)))

(defn on-movement-key-tick! [player-uuid movement-key]
  (client-session/with-current-client-session #(power-runtime/client-on-movement-key-tick! player-uuid movement-key)))

(defn on-movement-key-up! [player-uuid movement-key]
  (client-session/with-current-client-session #(power-runtime/client-on-movement-key-up! player-uuid movement-key)))

(defn abort-all! []
  (client-session/with-current-client-session #(power-runtime/client-abort-all!)))

(defn tick-client! []
  (session-cleanup/tick-connection-change!
   {:clear-owner-input-state! key-input/clear-owner-input-state!})
  (key-input/tick-input!)
  (particle/tick-particles!)
  (sound/tick-sounds!)
  (client-session/with-current-client-session #(power-runtime/client-tick!)))

(defn- on-client-tick [^TickEvent$ClientTickEvent evt]
  (when (= TickEvent$Phase/END (.phase evt))
    (tick-client!)))

(defn init! []
  (power-runtime/client-register-push-handlers!)
  (when-not (var-get #'*tick-listener-registered?*)
    (locking tick-listener-guard-lock
      (when-not (var-get #'*tick-listener-registered?*)
        (.addListener (MinecraftForge/EVENT_BUS)
                      EventPriority/NORMAL false TickEvent$ClientTickEvent
                      (reify java.util.function.Consumer
                        (accept [_ evt] (on-client-tick evt))))
        (alter-var-root #'*tick-listener-registered?* (constantly true)))))
  (log/info "Client runtime bridge initialized"))