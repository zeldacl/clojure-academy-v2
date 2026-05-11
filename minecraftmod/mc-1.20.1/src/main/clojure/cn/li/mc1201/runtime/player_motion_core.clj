(ns cn.li.mc1201.runtime.player-motion-core
  "Shared Minecraft-side player motion helpers (no loader API imports)."
  (:import [net.minecraft.world.entity Entity]
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
