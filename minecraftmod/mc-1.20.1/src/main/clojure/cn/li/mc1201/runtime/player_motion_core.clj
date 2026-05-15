(ns cn.li.mc1201.runtime.player-motion-core
  "Shared Minecraft-side player motion helpers (no loader API imports)."
  (:require [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.player-motion :as pm]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.world.entity Entity]
           [net.minecraft.server.level ServerPlayer]))

(defn set-velocity-for-player!
  [^ServerPlayer player x y z]
  (when player
    (.setDeltaMovement player x y z)
    (set! (.-hurtMarked ^Entity player) true)
    true))

(defn add-velocity-for-player!
  [^ServerPlayer player x y z]
  (when player
    (let [current (.getDeltaMovement player)]
      (.setDeltaMovement player
                         (+ (.x current) x)
                         (+ (.y current) y)
                         (+ (.z current) z))
      (set! (.-hurtMarked ^Entity player) true)
      true)))

(defn get-velocity-for-player
  [^ServerPlayer player]
  (when player
    (let [vel (.getDeltaMovement player)]
      {:x (.x vel)
       :y (.y vel)
       :z (.z vel)})))

(defn set-on-ground-for-player!
  [^ServerPlayer player on-ground?]
  (when player
    (.setOnGround player (boolean on-ground?))
    true))

(defn is-on-ground-for-player?
  [^ServerPlayer player]
  (boolean (and player (.onGround player))))

(defn dismount-riding-for-player!
  [^ServerPlayer player]
  (when player
    (when (.isPassenger player)
      (.stopRiding player))
    true))

(defn create-player-motion
  "Create an IPlayerMotion adapter using a platform-provided server supplier."
  [get-server]
  (reify pm/IPlayerMotion
    (set-velocity! [_ player-id x y z]
      (boolean (set-velocity-for-player! (query-core/get-player-by-uuid (get-server) player-id) x y z)))
    (add-velocity! [_ player-id x y z]
      (boolean (add-velocity-for-player! (query-core/get-player-by-uuid (get-server) player-id) x y z)))
    (get-velocity [_ player-id]
      (get-velocity-for-player (query-core/get-player-by-uuid (get-server) player-id)))
    (set-on-ground! [_ player-id on-ground?]
      (boolean (set-on-ground-for-player! (query-core/get-player-by-uuid (get-server) player-id) on-ground?)))
    (is-on-ground? [_ player-id]
      (is-on-ground-for-player? (query-core/get-player-by-uuid (get-server) player-id)))
    (dismount-riding! [_ player-id]
      (boolean (dismount-riding-for-player! (query-core/get-player-by-uuid (get-server) player-id))))))
