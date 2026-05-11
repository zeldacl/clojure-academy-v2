(ns cn.li.mc1201.runtime.entity-motion-core
  "Shared Minecraft-side entity motion helpers (no loader API imports)."
  (:import [net.minecraft.world.entity Entity]
           [net.minecraft.world.phys Vec3]))

(defn set-velocity-for-entity!
  [^Entity entity x y z]
  (when entity
    (.setDeltaMovement entity (double x) (double y) (double z))
    (set! (.-hurtMarked entity) true)
    true))

(defn add-velocity-for-entity!
  [^Entity entity x y z]
  (when entity
    (let [^Vec3 current (.getDeltaMovement entity)]
      (.setDeltaMovement entity
                         (+ (.x current) (double x))
                         (+ (.y current) (double y))
                         (+ (.z current) (double z)))
      (set! (.-hurtMarked entity) true)
      true)))

(defn discard-entity!
  [^Entity entity]
  (when entity
    (.discard entity)
    true))

(defn get-velocity-for-entity
  [^Entity entity]
  (when entity
    (let [^Vec3 vel (.getDeltaMovement entity)]
      {:x (.x vel)
       :y (.y vel)
       :z (.z vel)})))
