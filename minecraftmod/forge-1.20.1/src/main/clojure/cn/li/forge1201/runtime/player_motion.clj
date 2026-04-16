(ns cn.li.forge1201.runtime.player-motion
  "Forge implementation of IPlayerMotion protocol."
  (:require [cn.li.mcmod.platform.player-motion :as pm]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.entity Entity]
           [net.minecraftforge.server ServerLifecycleHooks]
           [java.util UUID]))


(defn- get-server ^MinecraftServer []
  (ServerLifecycleHooks/getCurrentServer))

(defn- get-player-by-uuid [uuid-str]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (let [uuid (UUID/fromString uuid-str)]
        (.getPlayer (.getPlayerList server) uuid)))
    (catch Exception e
      (log/warn "Failed to get player by UUID:" uuid-str (ex-message e))
      nil)))

(defn- set-velocity-impl! [player-uuid x y z]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (.setDeltaMovement player x y z)
      (set! (.-hurtMarked ^Entity player) true)
      true)
    (catch Exception e
      (log/warn "Failed to set velocity:" (ex-message e))
      false)))

(defn- add-velocity-impl! [player-uuid x y z]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (let [current (.getDeltaMovement player)]
        (.setDeltaMovement player
                          (+ (.x current) x)
                          (+ (.y current) y)
                          (+ (.z current) z))
        (set! (.-hurtMarked ^Entity player) true)
        true))
    (catch Exception e
      (log/warn "Failed to add velocity:" (ex-message e))
      false)))

(defn- get-velocity-impl [player-uuid]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (let [vel (.getDeltaMovement player)]
        {:x (.x vel)
         :y (.y vel)
         :z (.z vel)}))
    (catch Exception e
      (log/warn "Failed to get velocity:" (ex-message e))
      nil)))

(defn- set-on-ground-impl! [player-uuid on-ground?]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (.setOnGround player (boolean on-ground?))
      true)
    (catch Exception e
      (log/warn "Failed to set on-ground:" (ex-message e))
      false)))

(defn- is-on-ground-impl? [player-uuid]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (.onGround player))
    (catch Exception e
      (log/warn "Failed to check on-ground:" (ex-message e))
      false)))

(defn- dismount-riding-impl! [player-uuid]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (when (.isPassenger player)
        (.stopRiding player))
      true)
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
