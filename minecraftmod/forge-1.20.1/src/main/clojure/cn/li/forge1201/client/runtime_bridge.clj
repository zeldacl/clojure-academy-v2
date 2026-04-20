(ns cn.li.forge1201.client.runtime-bridge
  "CLIENT-ONLY Forge adapter for runtime hooks."
  (:require [cn.li.forge1201.client.effects.particle-bridge :as particle-bridge]
            [cn.li.forge1201.client.effects.sound-bridge :as sound-bridge]
            [cn.li.forge1201.client.key-input :as key-input]
            [cn.li.forge1201.client.overlay-state :as overlay-state]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.client.effect IntensifyEffectSpawner]
           [net.minecraft.client Minecraft]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.eventbus.api EventPriority]))

(defonce ^:private tick-listener-registered? (atom false))

(defn active-contexts []
  (power-runtime/client-active-contexts))

(defn latest-sync [player-uuid]
  (power-runtime/client-latest-sync player-uuid))

(defn local-player-item-id []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (let [stack (.getMainHandItem player)]
        (when (and stack (not (.isEmpty stack)))
          (when-let [key (.getKey BuiltInRegistries/ITEM (.getItem stack))]
            (str (.getNamespace key) ":" (.getPath key))))))))

(defn local-player-pos []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      {:x (.getX player) :y (.getY player) :z (.getZ player)})))

(defn local-player-eye-pos []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      {:x (.getX player) :y (+ (.getY player) 1.62) :z (.getZ player)})))

(defn local-player-look-end [distance]
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (let [look (.getLookAngle player)
            eye (local-player-eye-pos)]
        {:x (+ (:x eye) (* (.x look) distance))
         :y (+ (:y eye) (* (.y look) distance))
         :z (+ (:z eye) (* (.z look) distance))}))))

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
  (particle-bridge/tick-particles!)
  (sound-bridge/tick-sounds!)
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