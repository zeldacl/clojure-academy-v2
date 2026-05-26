(ns cn.li.forge1201.runtime.lifecycle
  "Forge player lifecycle hooks for runtime system."
  (:require [cn.li.mc1201.runtime.nbt-core :as runtime-nbt]
            [cn.li.mc1201.runtime.lifecycle-core :as lifecycle-core]
            [cn.li.mc1201.runtime.sync-core :as runtime-sync]
            [cn.li.forge1201.runtime.adapters.registry :as runtime-adapters-registry]
            [cn.li.mc1201.runtime.adapter-registry :as adapter-registry]
            [cn.li.forge1201.runtime.lifecycle-event-binding :as lifecycle-event-binding]
            [cn.li.forge1201.adapter.network :as runtime-network]
            [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.event.entity.player PlayerEvent$PlayerLoggedInEvent
                                                  PlayerEvent$PlayerLoggedOutEvent
                                   PlayerEvent$Clone
                                   PlayerEvent$PlayerChangedDimensionEvent]
           [net.minecraftforge.event.entity.living LivingDeathEvent]
           [net.minecraftforge.event TickEvent$PlayerTickEvent TickEvent$Phase]
           [net.minecraft.resources ResourceKey]
           [net.minecraft.server.level ServerPlayer]))


(defn- server-player [player]
  (when (instance? ServerPlayer player) player))

(defn- dimension-id [^ResourceKey dimension-key]
  (some-> dimension-key .location str))

(defn- server-tick-id [^ServerPlayer player]
  (some-> player .getServer .getTickCount))

(defn- server-session-id [^ServerPlayer player]
  (when-let [server (some-> player .getServer)]
    [:server (System/identityHashCode server)]))

(defn- lifecycle-owner [^ServerPlayer player]
  (cond-> {:server-session-id (server-session-id player)}
    (server-tick-id player) (assoc :server-tick-id (server-tick-id player))))

(defn- on-player-login [^PlayerEvent$PlayerLoggedInEvent evt]
  (when-let [^ServerPlayer p (server-player (.getEntity evt))]
    (lifecycle-core/on-player-login! p (merge (lifecycle-owner p)
                                              {:load-player-state! runtime-nbt/load-player-state!
                                               :mark-player-dirty! runtime-sync/mark-player-dirty!
                                               :send-sync-now! runtime-network/send-sync-to-client!
                                               :clear-player-dirty! runtime-sync/clear-player-dirty!}))))

(defn- on-player-logout [^PlayerEvent$PlayerLoggedOutEvent evt]
  (when-let [^ServerPlayer p (server-player (.getEntity evt))]
    (lifecycle-core/on-player-logout! p (merge (lifecycle-owner p)
                                               {:save-player-state! runtime-nbt/save-player-state!}))))

(defn- on-player-clone [^PlayerEvent$Clone evt]
  (when-let [^ServerPlayer oldp (server-player (.getOriginal evt))]
    (when-let [^ServerPlayer newp (server-player (.getEntity evt))]
      (lifecycle-core/on-player-clone! oldp newp (not (.isWasDeath evt))
                                       (merge (lifecycle-owner newp)
                                              {:clone-player-state! runtime-nbt/clone-player-state!
                                               :mark-player-dirty! runtime-sync/mark-player-dirty!
                                               :send-sync-now! runtime-network/send-sync-to-client!
                                               :clear-player-dirty! runtime-sync/clear-player-dirty!})))))

(defn- on-player-death [^LivingDeathEvent evt]
  (when-let [^ServerPlayer p (server-player (.getEntity evt))]
    (lifecycle-core/on-player-death! p (merge (lifecycle-owner p)
                                             {:save-player-state! runtime-nbt/save-player-state!}))))

(defn- on-player-dimension-change [^PlayerEvent$PlayerChangedDimensionEvent evt]
  (when-let [^ServerPlayer p (server-player (.getEntity evt))]
    (lifecycle-core/on-player-dimension-change! p
                                                (dimension-id (.getFrom evt))
                                                (dimension-id (.getTo evt))
                      (merge (lifecycle-owner p)
                        {:mark-player-dirty! runtime-sync/mark-player-dirty!
                    :tick-sync! runtime-sync/tick-sync!
                    :send-sync-fn runtime-network/send-sync-to-client!
                    :send-sync-now! runtime-network/send-sync-to-client!
                    :clear-player-dirty! runtime-sync/clear-player-dirty!}))))

(defn- on-player-tick [^TickEvent$PlayerTickEvent evt]
  (when (and (= TickEvent$Phase/END (.phase evt))
             (server-player (.player evt)))
    (let [^ServerPlayer p (.player evt)]
      (lifecycle-core/on-player-tick! p (merge (lifecycle-owner p)
                                               {:mark-player-dirty! runtime-sync/mark-player-dirty!
                                                :tick-sync! runtime-sync/tick-sync!
                                                :send-sync-fn runtime-network/send-sync-to-client!})))))

(defn init-common!
  "Register all forge-side lifecycle listeners for runtime bridge."
  []
  (adapter-registry/run-install-steps! "forge-1.20.1" runtime-adapters-registry/runtime-install-steps)
  (runtime-network/init!)
  (lifecycle-event-binding/register-lifecycle-listeners!
    {:on-player-login on-player-login
     :on-player-logout on-player-logout
     :on-player-clone on-player-clone
     :on-player-death on-player-death
      :on-player-dimension-change on-player-dimension-change
     :on-player-tick on-player-tick})

  ;; Initialize damage handlers after all protocols are installed
  (power-runtime/init-damage-handlers!)

  (log/info "Forge runtime lifecycle initialized"))
