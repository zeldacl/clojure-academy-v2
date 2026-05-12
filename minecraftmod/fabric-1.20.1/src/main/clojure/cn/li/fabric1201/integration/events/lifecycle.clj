(ns cn.li.fabric1201.integration.events.lifecycle
  "Fabric player/server lifecycle handlers extracted from monolithic events namespace."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.runtime.nbt-core :as runtime-nbt]
            [cn.li.mc1201.runtime.sync-core :as runtime-sync]
            [cn.li.fabric1201.runtime.network :as runtime-network]
            [cn.li.mc1201.runtime.lifecycle-core :as lifecycle-core])
  (:import [net.minecraft.server.level ServerPlayer]))

(defn handle-player-login
  [^ServerPlayer player]
  (try
    (lifecycle-core/on-player-login! player {:load-player-state! runtime-nbt/load-player-state!
                                             :mark-player-dirty! runtime-sync/mark-player-dirty!})
    (catch Throwable t
      (log/error "Error handling player login event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-player-logout
  [^ServerPlayer player]
  (try
    (lifecycle-core/on-player-logout! player {:save-player-state! runtime-nbt/save-player-state!})
    (catch Throwable t
      (log/error "Error handling player logout event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-player-clone
  [^ServerPlayer old-player ^ServerPlayer new-player alive]
  (try
    (lifecycle-core/on-player-clone! old-player new-player alive {:clone-player-state! runtime-nbt/clone-player-state!})
    (catch Throwable t
      (log/error "Error handling player clone event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-player-death
  [entity]
  (try
    (when (instance? ServerPlayer entity)
      (let [^ServerPlayer p entity]
        (lifecycle-core/on-player-death! p {:save-player-state! runtime-nbt/save-player-state!})))
    (catch Throwable t
      (log/error "Error handling player death event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-player-tick
  [server]
  (try
    (doseq [^ServerPlayer player (.getPlayers (.getPlayerList server))]
      (lifecycle-core/on-player-tick! player {:mark-player-dirty! runtime-sync/mark-player-dirty!
                                              :tick-sync! runtime-sync/tick-sync!
                                              :send-sync-fn runtime-network/send-sync-to-client!}))
    (catch Throwable t
      (log/error "Error handling player tick event:" (.getMessage t))
      (.printStackTrace t))))
