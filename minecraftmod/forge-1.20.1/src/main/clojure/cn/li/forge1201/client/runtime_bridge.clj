(ns cn.li.forge1201.client.runtime-bridge
  "CLIENT-ONLY Forge adapter for AC-owned runtime hooks."
  (:require [cn.li.forge1201.client.overlay-state :as overlay-state]
            [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.client.effect IntensifyEffectSpawner]
           [net.minecraft.client Minecraft]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.eventbus.api EventPriority]))

(defonce ^:private tick-listener-registered? (atom false))

(defn active-contexts []
  (ability-runtime/client-active-contexts))

(defn latest-sync [player-uuid]
  (ability-runtime/client-latest-sync player-uuid))

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

(defn notify-railgun-coin-throw-client! [player-uuid]
  (ability-runtime/client-notify-railgun-coin-throw! player-uuid))

(defn railgun-charge-visual-state [player-uuid]
  (ability-runtime/client-railgun-charge-visual-state player-uuid))

(defn slot-visual-state [player-uuid key-idx]
  (ability-runtime/client-slot-visual-state player-uuid key-idx))

(defn body-intensify-charge-visual-state []
  (ability-runtime/client-body-intensify-charge-visual-state))

(defn current-charging-visual-state []
  (ability-runtime/client-current-charging-visual-state))

(defn on-slot-key-down! [player-uuid key-idx]
  (ability-runtime/client-on-slot-key-down! player-uuid key-idx))

(defn on-slot-key-tick! [player-uuid key-idx]
  (ability-runtime/client-on-slot-key-tick! player-uuid key-idx))

(defn on-slot-key-up! [player-uuid key-idx]
  (ability-runtime/client-on-slot-key-up! player-uuid key-idx))

(defn abort-all! []
  (ability-runtime/client-abort-all!))

(defn tick-client! []
  (when-let [tick-input-fn (resolve 'cn.li.forge1201.client.key-input/tick-input!)]
    (@tick-input-fn))
  (when-let [tick-particles-fn (resolve 'cn.li.forge1201.client.effects.particle-bridge/tick-particles!)]
    (@tick-particles-fn))
  (when-let [tick-sounds-fn (resolve 'cn.li.forge1201.client.effects.sound-bridge/tick-sounds!)]
    (@tick-sounds-fn))
  (ability-runtime/client-tick!))

(defn- on-client-tick [^TickEvent$ClientTickEvent evt]
  (when (= TickEvent$Phase/END (.phase evt))
    (tick-client!)))

(defn init! []
  (ability-runtime/client-register-push-handlers!)
  (when (compare-and-set! tick-listener-registered? false true)
    (.addListener (MinecraftForge/EVENT_BUS)
                  EventPriority/NORMAL false TickEvent$ClientTickEvent
                  (reify java.util.function.Consumer
                    (accept [_ evt] (on-client-tick evt)))))
  (log/info "Client runtime bridge initialized"))