(ns cn.li.forge1201.runtime.player-motion
  "Forge implementation of IPlayerMotion protocol."
  (:require [cn.li.mc1201.runtime.player-motion-core :as core]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.player-motion :as pm]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraftforge.server ServerLifecycleHooks]))


(defn- get-server ^MinecraftServer []
  (ServerLifecycleHooks/getCurrentServer))

(defn- get-player-by-uuid [uuid-str]
  (try
    (query-core/get-player-by-uuid (get-server) uuid-str)
    (catch Exception e
      (log/warn "Failed to get player by UUID:" uuid-str (ex-message e))
      nil)))

(defn- set-velocity-impl! [player-uuid x y z]
  (try
    (boolean (core/set-velocity-for-player! (get-player-by-uuid player-uuid) x y z))
    (catch Exception e
      (log/warn "Failed to set velocity:" (ex-message e))
      false)))

(defn- add-velocity-impl! [player-uuid x y z]
  (try
    (boolean (core/add-velocity-for-player! (get-player-by-uuid player-uuid) x y z))
    (catch Exception e
      (log/warn "Failed to add velocity:" (ex-message e))
      false)))

(defn- get-velocity-impl [player-uuid]
  (try
    (core/get-velocity-for-player (get-player-by-uuid player-uuid))
    (catch Exception e
      (log/warn "Failed to get velocity:" (ex-message e))
      nil)))

(defn- set-on-ground-impl! [player-uuid on-ground?]
  (try
    (boolean (core/set-on-ground-for-player! (get-player-by-uuid player-uuid) on-ground?))
    (catch Exception e
      (log/warn "Failed to set on-ground:" (ex-message e))
      false)))

(defn- is-on-ground-impl? [player-uuid]
  (try
    (core/is-on-ground-for-player? (get-player-by-uuid player-uuid))
    (catch Exception e
      (log/warn "Failed to check on-ground:" (ex-message e))
      false)))

(defn- dismount-riding-impl! [player-uuid]
  (try
    (boolean (core/dismount-riding-for-player! (get-player-by-uuid player-uuid)))
    (catch Exception e
      (log/warn "Failed to dismount riding:" (ex-message e))
      false)))

(defn forge-player-motion []
  (reify pm/IPlayerMotion
    (set-velocity! [_ player-id x y z]
      (set-velocity-impl! player-id x y z))
    (add-velocity! [_ player-id x y z]
      (add-velocity-impl! player-id x y z))
    (get-velocity [_ player-id]
      (get-velocity-impl player-id))
    (set-on-ground! [_ player-id on-ground?]
      (set-on-ground-impl! player-id on-ground?))
    (is-on-ground? [_ player-id]
      (is-on-ground-impl? player-id))
    (dismount-riding! [_ player-id]
      (dismount-riding-impl! player-id))))

(defn install-player-motion! []
  (alter-var-root #'pm/*player-motion*
                  (constantly (forge-player-motion)))
  (log/info "Forge player motion installed"))
