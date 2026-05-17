(ns cn.li.fabric1201.runtime.entity-damage
  "Fabric implementation of IEntityDamage protocol."
  (:require [cn.li.fabric1201.adapter.server-context :as server-context]
            [cn.li.mc1201.reflect-util :as ru]
            [cn.li.mc1201.runtime.entity-damage-adapter :as adapter]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.entity-damage :as ped]
            [cn.li.mcmod.util.log :as log]))

(defn- get-server []
  (server-context/get-server))

(defonce ^:private living-entity-class
  (delay (ru/class-noinit "net.minecraft.world.entity.LivingEntity")))

(defn- living-entity? [entity]
  (instance? @living-entity-class entity))

(defn fabric-entity-damage []
  (adapter/create-entity-damage
    get-server
    {:resolve-level-fn (fn [server world-id] (query-core/resolve-level server world-id))
     :get-entity-by-uuid-fn query-core/get-entity-by-uuid
     :get-living-entities-in-aabb-fn (fn [level aabb]
                                       (.getEntitiesOfClass level @living-entity-class aabb))
     :living-entity?-fn living-entity?
     :apply-hurt-fn (fn [entity dmg-source damage]
                      (.hurt entity dmg-source (float damage)))}))

(defn install-entity-damage! []
  (server-context/install-server-context!)
  (alter-var-root #'ped/*entity-damage*
                  (constantly (fabric-entity-damage)))
  (log/info "Fabric entity damage installed"))
