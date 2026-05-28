(ns cn.li.fabric1201.runtime.world-effects
  "Fabric implementation of IWorldEffects protocol."
  (:require [cn.li.fabric1201.adapter.server-context :as server-context]
            [cn.li.mc1201.runtime.adapter.world-effects :as world-effects]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.world.level Level$ExplosionInteraction]
           [net.minecraft.world.entity Entity EntityType LightningBolt]
           [net.minecraft.world.level.block Block]
           [net.minecraft.world.phys AABB]))

(def ^:private install-guard-lock
  (Object.))

(def ^:private ^:dynamic *installed?*
  false)

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn fabric-world-effects []
  (world-effects/create-world-effects
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
    :resolve-entity-id-fn (fn [^Entity entity]
                             (str (.getDescriptionId (.getType entity))))
    :block-id-fn (fn [^Block block _block-state]
                    (str (.getDescriptionId block)))}))

(defn install-world-effects! []
  (if (var-get #'*installed?*)
    (log/info "Fabric world effects already installed, skipping")
    (locking install-guard-lock
      (if (var-get #'*installed?*)
        (log/info "Fabric world effects already installed, skipping")
        (do
          (server-context/install-server-context!)
          (world-effects/install-world-effects! (fabric-world-effects)
                                                "Fabric world effects")
          (alter-var-root #'*installed?* (constantly true)))))))
