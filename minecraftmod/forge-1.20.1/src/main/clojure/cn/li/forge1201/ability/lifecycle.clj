(ns cn.li.forge1201.ability.lifecycle
  "Forge player lifecycle hooks for ability system."
  (:require [cn.li.forge1201.ability.nbt :as ability-nbt]
            [cn.li.forge1201.ability.sync :as ability-sync]
            [cn.li.forge1201.ability.network :as ability-network]
            [cn.li.forge1201.ability.store :as ability-store]
            [cn.li.forge1201.ability.world-effects :as world-effects]
            [cn.li.forge1201.ability.entity-damage :as entity-damage]
            [cn.li.forge1201.ability.raycast :as raycast]
            [cn.li.forge1201.ability.potion-effects :as potion-effects]
            [cn.li.forge1201.ability.teleportation :as teleportation]
            [cn.li.forge1201.ability.saved-locations :as saved-locations]
            [cn.li.forge1201.ability.player-motion :as player-motion]
            [cn.li.forge1201.ability.block-manipulation :as block-manipulation]
            [cn.li.forge1201.ability.damage-interception :as damage-interception]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.damage-handler :as damage-handler]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraftforge.event.entity.player PlayerEvent$PlayerLoggedInEvent
                                                  PlayerEvent$PlayerLoggedOutEvent
                                                  PlayerEvent$Clone]
           [net.minecraftforge.event.entity.living LivingDeathEvent]
           [net.minecraftforge.event TickEvent$PlayerTickEvent TickEvent$Phase]
           [net.minecraft.server.level ServerPlayer]))

(set! *warn-on-reflection* true)

(defn- server-player [player]
  (when (instance? ServerPlayer player) player))

(defn- on-player-login [^PlayerEvent$PlayerLoggedInEvent evt]
  (when-let [^ServerPlayer p (server-player (.getEntity evt))]
    (ability-nbt/load-player-state! p)
    (ability-sync/mark-player-dirty! (str (.getUUID p)))))

(defn- on-player-logout [^PlayerEvent$PlayerLoggedOutEvent evt]
  (when-let [^ServerPlayer p (server-player (.getEntity evt))]
    (ability-nbt/save-player-state! p)
    (ctx-mgr/abort-player-contexts! (str (.getUUID p)))
    (ps/remove-player-state! (str (.getUUID p)))))

(defn- on-player-clone [^PlayerEvent$Clone evt]
  (when (not (.isWasDeath evt))
    (when-let [^ServerPlayer oldp (server-player (.getOriginal evt))]
      (when-let [^ServerPlayer newp (server-player (.getEntity evt))]
        (ability-nbt/clone-player-state! oldp newp)))))

(defn- on-player-death [^LivingDeathEvent evt]
  (when-let [^ServerPlayer p (server-player (.getEntity evt))]
    (ctx-mgr/abort-player-contexts! (str (.getUUID p)))
    (ability-nbt/save-player-state! p)))

(defn- on-player-tick [^TickEvent$PlayerTickEvent evt]
  (when (and (= TickEvent$Phase/END (.phase evt))
             (server-player (.player evt)))
    (let [^ServerPlayer p (.player evt)
          uuid (str (.getUUID p))]
      (ps/get-or-create-player-state! uuid)
      (ps/server-tick-player! uuid nil)
      (ability-sync/mark-player-dirty! uuid)
      (ability-sync/tick-sync! ability-network/send-sync-to-client!)
      (ctx-mgr/tick-context-manager!))))

(defn init-common!
  "Register all forge-side lifecycle listeners for ability runtime."
  []
  (ability-store/install-store!)
  (world-effects/install-world-effects!)
  (entity-damage/install-entity-damage!)
  (raycast/install-raycast!)
  (potion-effects/install-potion-effects!)
  (teleportation/install-teleportation!)
  (saved-locations/install-saved-locations!)
  (player-motion/install-player-motion!)
  (block-manipulation/install-block-manipulation!)
  (damage-interception/install-damage-interception!)
  (ability-network/init!)
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
  (damage-handler/init-damage-handlers!)

  (log/info "Forge ability lifecycle initialized"))
