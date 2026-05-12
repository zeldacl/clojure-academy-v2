(ns cn.li.fabric1201.runtime.world-effects
  "Fabric implementation of IWorldEffects protocol."
  (:require [cn.li.fabric1201.runtime.server-context :as server-context]
            [cn.li.mc1201.runtime.world-effects-adapter :as adapter]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.world-effects :as pwe]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.world.level Level$ExplosionInteraction]
           [net.minecraft.world.entity EntityType LightningBolt]
           [net.minecraft.world.phys AABB]))

(defonce ^:private installed? (atom false))

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn fabric-world-effects []
  (adapter/create-world-effects
    get-server
    {:resolve-level-fn (fn [server world-id] (query-core/resolve-level-strict server world-id))
     :spawn-lightning-fn (fn [^ServerLevel level x y z]
                           (let [bolt (.create EntityType/LIGHTNING_BOLT level)]
                             (when bolt
                               (.moveTo ^LightningBolt bolt (double x) (double y) (double z))
                               (boolean (.addFreshEntity level bolt)))))
     :create-explosion-fn (fn [^ServerLevel level x y z radius fire?]
                            (try
                              (.explode level nil (double x) (double y) (double z) (float radius) (boolean fire?) Level$ExplosionInteraction/MOB)
                              true
                              (catch Throwable _
                                (.explode level nil (double x) (double y) (double z) (float radius) Level$ExplosionInteraction/MOB)
                                true)))
     :get-entities-in-aabb-fn (fn [^ServerLevel l ^AABB aabb]
                                (.getEntities l nil aabb))
     :resolve-entity-id-fn (fn [entity]
                             (str (.getDescriptionId (.getType entity))))
     :block-id-fn (fn [block _block-state]
                    (str (.getDescriptionId block)))}))

(defn install-world-effects! []
  (if-not (compare-and-set! installed? false true)
    (log/info "Fabric world effects already installed, skipping")
    (do
      (server-context/install-server-context!)
      (alter-var-root #'pwe/*world-effects*
                      (constantly (fabric-world-effects)))
      (log/info "Fabric world effects installed"))))
