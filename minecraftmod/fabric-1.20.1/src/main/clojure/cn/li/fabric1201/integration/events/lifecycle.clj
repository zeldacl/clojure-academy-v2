(ns cn.li.fabric1201.integration.events.lifecycle
  "Fabric player/server lifecycle handlers extracted from monolithic events namespace."
  (:require [cn.li.mc1201.integration.event-support :as event-support]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.runtime.nbt-core :as runtime-nbt]
            [cn.li.mc1201.runtime.sync-core :as runtime-sync]
            [cn.li.fabric1201.adapter.network :as runtime-network]
            [cn.li.mc1201.runtime.lifecycle-core :as lifecycle-core])
  (:import [net.minecraft.server.level ServerPlayer]))

(defn handle-player-login
  [^ServerPlayer player]
  (event-support/guarded-call
    "fabric player login"
    nil
    (fn []
      (lifecycle-core/on-player-login! player {:load-player-state! runtime-nbt/load-player-state!
                                               :mark-player-dirty! runtime-sync/mark-player-dirty!}))))

(defn handle-player-logout
  [^ServerPlayer player]
  (event-support/guarded-call
    "fabric player logout"
    nil
    (fn []
      (lifecycle-core/on-player-logout! player {:save-player-state! runtime-nbt/save-player-state!}))))

(defn handle-player-clone
  [^ServerPlayer old-player ^ServerPlayer new-player alive]
  (event-support/guarded-call
    "fabric player clone"
    nil
    (fn []
      (lifecycle-core/on-player-clone! old-player new-player alive {:clone-player-state! runtime-nbt/clone-player-state!}))))

(defn handle-player-death
  [entity]
  (event-support/guarded-call
    "fabric player death"
    nil
    (fn []
      (when (instance? ServerPlayer entity)
        (let [^ServerPlayer p entity]
          (lifecycle-core/on-player-death! p {:save-player-state! runtime-nbt/save-player-state!}))))))

(defn handle-player-tick
  [server]
  (event-support/guarded-call
    "fabric player tick"
    nil
    (fn []
      (doseq [^ServerPlayer player (.getPlayers (.getPlayerList server))]
        (lifecycle-core/on-player-tick! player {:mark-player-dirty! runtime-sync/mark-player-dirty!
                                                :tick-sync! runtime-sync/tick-sync!
                                                :send-sync-fn runtime-network/send-sync-to-client!})))))
