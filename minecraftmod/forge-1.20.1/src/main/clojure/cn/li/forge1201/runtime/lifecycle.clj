(ns cn.li.forge1201.runtime.lifecycle
  "Forge player lifecycle hooks for runtime system."
  (:require [cn.li.mc1201.runtime.nbt-core :as runtime-nbt]
            [cn.li.mc1201.runtime.lifecycle-core :as lifecycle-core]
            [cn.li.mc1201.runtime.sync-core :as runtime-sync]
            [cn.li.forge1201.runtime.install :as runtime-install]
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
    (lifecycle-core/on-player-login! p {:load-player-state! runtime-nbt/load-player-state!
                                        :mark-player-dirty! runtime-sync/mark-player-dirty!})))

(defn- on-player-logout [^PlayerEvent$PlayerLoggedOutEvent evt]
  (when-let [^ServerPlayer p (server-player (.getEntity evt))]
    (lifecycle-core/on-player-logout! p {:save-player-state! runtime-nbt/save-player-state!})))

(defn- on-player-clone [^PlayerEvent$Clone evt]
  (when (not (.isWasDeath evt))
    (when-let [^ServerPlayer oldp (server-player (.getOriginal evt))]
      (when-let [^ServerPlayer newp (server-player (.getEntity evt))]
        (lifecycle-core/on-player-clone! oldp newp true {:clone-player-state! runtime-nbt/clone-player-state!})))))

(defn- on-player-death [^LivingDeathEvent evt]
  (when-let [^ServerPlayer p (server-player (.getEntity evt))]
    (lifecycle-core/on-player-death! p {:save-player-state! runtime-nbt/save-player-state!})))

(defn- on-player-tick [^TickEvent$PlayerTickEvent evt]
  (when (and (= TickEvent$Phase/END (.phase evt))
             (server-player (.player evt)))
    (let [^ServerPlayer p (.player evt)]
      (lifecycle-core/on-player-tick! p {:mark-player-dirty! runtime-sync/mark-player-dirty!
                                         :tick-sync! runtime-sync/tick-sync!
                                         :send-sync-fn runtime-network/send-sync-to-client!}))))

(defn init-common!
  "Register all forge-side lifecycle listeners for runtime bridge."
  []
  (runtime-install/install-runtime-adapters!)
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
