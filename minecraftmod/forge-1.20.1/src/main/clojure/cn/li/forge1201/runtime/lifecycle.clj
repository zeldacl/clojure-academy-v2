(ns cn.li.forge1201.runtime.lifecycle
  "Forge player lifecycle hooks for runtime system."
  (:require [cn.li.forge1201.runtime.nbt :as runtime-nbt]
            [cn.li.forge1201.runtime.sync :as runtime-sync]
            [cn.li.forge1201.runtime.network :as runtime-network]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraftforge.event.entity.player PlayerEvent$PlayerLoggedInEvent
                                                  PlayerEvent$PlayerLoggedOutEvent
                                                  PlayerEvent$Clone]
           [net.minecraftforge.event.entity.living LivingDeathEvent]
           [net.minecraftforge.event TickEvent$PlayerTickEvent TickEvent$Phase]
           [net.minecraft.server.level ServerPlayer]))


(defn- server-player [player]
  (when (instance? ServerPlayer player) player))

(defn- on-player-login [^PlayerEvent$PlayerLoggedInEvent evt]
  (when-let [^ServerPlayer p (server-player (.getEntity evt))]
    (runtime-nbt/load-player-state! p)
    (power-runtime/on-player-login! (str (.getUUID p)))
    (runtime-sync/mark-player-dirty! (str (.getUUID p)))))

(defn- on-player-logout [^PlayerEvent$PlayerLoggedOutEvent evt]
  (when-let [^ServerPlayer p (server-player (.getEntity evt))]
    (runtime-nbt/save-player-state! p)
    (power-runtime/on-player-logout! (str (.getUUID p)))))

(defn- on-player-clone [^PlayerEvent$Clone evt]
  (when (not (.isWasDeath evt))
    (when-let [^ServerPlayer oldp (server-player (.getOriginal evt))]
      (when-let [^ServerPlayer newp (server-player (.getEntity evt))]
        (runtime-nbt/clone-player-state! oldp newp)
        (power-runtime/on-player-clone! (str (.getUUID oldp))
                                          (str (.getUUID newp)))))))

(defn- on-player-death [^LivingDeathEvent evt]
  (when-let [^ServerPlayer p (server-player (.getEntity evt))]
    (power-runtime/on-player-death! (str (.getUUID p)))
    (runtime-nbt/save-player-state! p)))

(defn- on-player-tick [^TickEvent$PlayerTickEvent evt]
  (when (and (= TickEvent$Phase/END (.phase evt))
             (server-player (.player evt)))
    (let [^ServerPlayer p (.player evt)
          uuid (str (.getUUID p))]
      (power-runtime/on-player-tick! uuid)
      (runtime-sync/mark-player-dirty! uuid)
      (runtime-sync/tick-sync! runtime-network/send-sync-to-client!))))

(defn- try-install! [ns-sym fn-sym label]
  (try
    (require ns-sym)
    (if-let [f (resolve fn-sym)]
      (f)
      (log/warn "Install function not found for" label "(" fn-sym ")"))
    (catch Exception e
      (log/warn "Failed to install" label ":" (ex-message e)))))

(defn init-common!
  "Register all forge-side lifecycle listeners for runtime bridge."
  []
  ;; Keep this minimal and enable only adapters currently relied upon by migrated runtime features.
  (try-install! 'cn.li.forge1201.runtime.entity-damage
                'cn.li.forge1201.runtime.entity-damage/install-entity-damage!
                "entity-damage")
  (try-install! 'cn.li.forge1201.runtime.raycast
                'cn.li.forge1201.runtime.raycast/install-raycast!
                "raycast")
  (try-install! 'cn.li.forge1201.runtime.interop
                'cn.li.forge1201.runtime.interop/install-runtime-interop!
                "runtime-interop")
  (try-install! 'cn.li.forge1201.runtime.world-effects
                'cn.li.forge1201.runtime.world-effects/install-world-effects!
                "world-effects")
  (try-install! 'cn.li.forge1201.runtime.potion-effects
                'cn.li.forge1201.runtime.potion-effects/install-potion-effects!
                "potion-effects")
  (try-install! 'cn.li.forge1201.runtime.teleportation
                'cn.li.forge1201.runtime.teleportation/install-teleportation!
                "teleportation")
  (try-install! 'cn.li.forge1201.runtime.saved-locations
                'cn.li.forge1201.runtime.saved-locations/install-saved-locations!
                "saved-locations")
  (try-install! 'cn.li.forge1201.runtime.player-motion
                'cn.li.forge1201.runtime.player-motion/install-player-motion!
                "player-motion")
  (try-install! 'cn.li.forge1201.runtime.entity-motion
                'cn.li.forge1201.runtime.entity-motion/install-entity-motion!
                "entity-motion")
  (try-install! 'cn.li.forge1201.runtime.entity-query
                'cn.li.forge1201.runtime.entity-query/install-entity-query!
                "entity-query")
  (try-install! 'cn.li.forge1201.runtime.block-manipulation
                'cn.li.forge1201.runtime.block-manipulation/install-block-manipulation!
                "block-manipulation")
  (try-install! 'cn.li.forge1201.runtime.damage-interception
                'cn.li.forge1201.runtime.damage-interception/install-damage-interception!
                "damage-interception")
  (runtime-network/init!)
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false PlayerEvent$PlayerLoggedInEvent
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-player-login evt))))
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false PlayerEvent$PlayerLoggedOutEvent
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-player-logout evt))))
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false PlayerEvent$Clone
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-player-clone evt))))
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false LivingDeathEvent
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-player-death evt))))
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false TickEvent$PlayerTickEvent
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-player-tick evt))))

  ;; Initialize damage handlers after all protocols are installed
  (power-runtime/init-damage-handlers!)

  (log/info "Forge runtime lifecycle initialized"))
