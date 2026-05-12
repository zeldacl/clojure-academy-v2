(ns cn.li.mc1201.runtime.entity-motion-core
  "Shared Minecraft-side entity motion helpers (no loader API imports)."
  (:require [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.entity-motion :as pem]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.world.entity Entity]
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

(defn resolve-entity
  [^MinecraftServer server world-id entity-uuid]
  (try
    (when server
      (some-> (query-core/resolve-level server world-id)
              (query-core/get-entity-by-uuid entity-uuid)))
    (catch Exception e
      (log/warn "Failed to resolve entity:" world-id entity-uuid (ex-message e))
      nil)))

(defn create-entity-motion
  "Create an IEntityMotion adapter using a platform-provided server supplier."
  [get-server]
  (reify pem/IEntityMotion
    (set-velocity! [_ world-id entity-uuid x y z]
      (boolean (set-velocity-for-entity! (resolve-entity (get-server) world-id entity-uuid) x y z)))
    (add-velocity! [_ world-id entity-uuid x y z]
      (boolean (add-velocity-for-entity! (resolve-entity (get-server) world-id entity-uuid) x y z)))
    (discard-entity! [_ world-id entity-uuid]
      (boolean (discard-entity! (resolve-entity (get-server) world-id entity-uuid))))
    (get-velocity [_ world-id entity-uuid]
      (get-velocity-for-entity (resolve-entity (get-server) world-id entity-uuid)))))
