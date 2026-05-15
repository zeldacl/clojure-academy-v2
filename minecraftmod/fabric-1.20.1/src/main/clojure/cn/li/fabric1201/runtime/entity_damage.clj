(ns cn.li.fabric1201.runtime.entity-damage
  "Fabric implementation of IEntityDamage protocol."
  (:require [cn.li.fabric1201.adapter.server-context :as server-context]
            [cn.li.mc1201.runtime.entity-damage-adapter :as adapter]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.entity-damage :as ped]
            [cn.li.mcmod.util.log :as log]))

(defn- get-server []
  (server-context/get-server))

(defn- class-noinit [^String class-name]
  (Class/forName class-name false (.getContextClassLoader (Thread/currentThread))))

(defonce ^:private living-entity-class
  (delay (class-noinit "net.minecraft.world.entity.LivingEntity")))

(defonce ^:private aabb-class
  (delay (class-noinit "net.minecraft.world.phys.AABB")))

(defn- living-entity? [entity]
  (instance? @living-entity-class entity))

(defn- make-aabb [min-x min-y min-z max-x max-y max-z]
  (.newInstance (.getConstructor @aabb-class (into-array Class [Double/TYPE Double/TYPE Double/TYPE Double/TYPE Double/TYPE Double/TYPE]))
                (object-array [(double min-x) (double min-y) (double min-z)
                               (double max-x) (double max-y) (double max-z)])))

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
