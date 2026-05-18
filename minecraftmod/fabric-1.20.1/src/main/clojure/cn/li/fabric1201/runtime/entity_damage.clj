(ns cn.li.fabric1201.runtime.entity-damage
  "Fabric implementation of IEntityDamage protocol."
  (:require [cn.li.fabric1201.adapter.server-context :as server-context]
            [cn.li.mc1201.reflect-util :as ru]
            [cn.li.mc1201.runtime.adapter.entity-damage :as entity-damage])
  (:import [net.minecraft.world.level Level]
           [net.minecraft.world.phys AABB]))

(defonce ^:private living-entity-class
  (delay (ru/class-noinit "net.minecraft.world.entity.LivingEntity")))

(defn- living-entity? [entity]
  (instance? @living-entity-class entity))

(defn fabric-entity-damage []
  (entity-damage/create-entity-damage
    server-context/get-server
    {:get-living-entities-in-aabb-fn (fn [^Level level ^AABB aabb]
                                       (.getEntitiesOfClass level ^Class @living-entity-class aabb))
     :living-entity?-fn living-entity?}))

(defn install-entity-damage! []
  (server-context/install-server-context!)
  (entity-damage/install-entity-damage! (fabric-entity-damage)
                                        "Fabric entity damage"))
