(ns cn.li.forge1201.runtime.entity-damage
  "Forge implementation of IEntityDamage protocol."
  (:require [cn.li.mc1201.runtime.entity-damage-core :as core]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.entity-damage :as ped]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [cn.li.forge1201.bridge ForgeRuntimeBridge]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.world.entity Entity LivingEntity]
           [net.minecraft.world.phys AABB]
           [net.minecraftforge.server ServerLifecycleHooks]))

(defn- get-server ^MinecraftServer []
  (ServerLifecycleHooks/getCurrentServer))

(defn- apply-direct-damage-impl! [world-id entity-uuid damage source-type]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (when-let [^ServerLevel level (query-core/resolve-level server world-id)]
        (when-let [entity (query-core/get-entity-by-uuid level entity-uuid)]
          (when (ForgeRuntimeBridge/isLivingEntity ^Entity entity)
            (let [^LivingEntity living entity
                  dmg-source (core/resolve-damage-source level source-type)]
              (.hurt living dmg-source (float damage))
              true)))))
    (catch Exception e
      (log/warn "Failed to apply direct damage:" (ex-message e))
      false)))

(defn- apply-aoe-damage-impl! [world-id x y z radius damage source-type falloff?]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (when-let [^ServerLevel level (query-core/resolve-level server world-id)]
        (let [origin-pos {:x x :y y :z z}
              aabb (AABB. (- x radius) (- y radius) (- z radius)
                          (+ x radius) (+ y radius) (+ z radius))
              entities (ForgeRuntimeBridge/getLivingEntitiesInAabb level aabb)
              dmg-source (core/resolve-damage-source level source-type)
              damaged (atom [])]
          (doseq [^LivingEntity entity entities]
          (let [target-pos (core/entity-pos-map entity)
            actual-damage (core/compute-aoe-damage
                    origin-pos target-pos radius damage falloff?)]
              (when (> actual-damage 0.0)
                (.hurt entity dmg-source (float actual-damage))
                (swap! damaged conj (str (.getUUID entity))))))
          @damaged)))
    (catch Exception e
      (log/warn "Failed to apply AOE damage:" (ex-message e))
      [])))

(defn- find-reflection-target [^ServerLevel level ^LivingEntity current-entity max-radius]
  (let [current-pos (core/entity-pos-map current-entity)
        aabb (AABB. (- (:x current-pos) max-radius)
                    (- (:y current-pos) max-radius)
                    (- (:z current-pos) max-radius)
                    (+ (:x current-pos) max-radius)
                    (+ (:y current-pos) max-radius)
                    (+ (:z current-pos) max-radius))
        candidates (mapv core/candidate-map (ForgeRuntimeBridge/getLivingEntitiesInAabb level aabb))
        target-uuid (core/select-reflection-target-uuid
                      (str (.getUUID current-entity))
                      current-pos
                      candidates
                      max-radius)]
    (when target-uuid
      (query-core/get-entity-by-uuid level target-uuid))))

(defn- apply-reflection-damage-impl! [world-id entity-uuid damage source-type reflection-count max-reflections]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (when-let [^ServerLevel level (query-core/resolve-level server world-id)]
        (when-let [entity (query-core/get-entity-by-uuid level entity-uuid)]
          (when (ForgeRuntimeBridge/isLivingEntity ^Entity entity)
            (let [^LivingEntity living entity
                  dmg-source (core/resolve-damage-source level source-type)
                  search-radius (double (power-runtime/get-reflection-search-radius))
                  hit-entities (atom [(str (.getUUID living))])]
              (.hurt living dmg-source (float damage))
              (loop [^LivingEntity current-entity living
                     current-damage damage
                     reflection-num reflection-count]
                (when (< reflection-num max-reflections)
                  (when-let [^LivingEntity next-entity (find-reflection-target level current-entity search-radius)]
                    (let [reflected-damage (core/compute-reflected-damage current-damage)]
                      (.hurt next-entity dmg-source (float reflected-damage))
                      (swap! hit-entities conj (str (.getUUID next-entity)))
                      (recur next-entity reflected-damage (inc reflection-num))))))
              @hit-entities)))))
    (catch Exception e
      (log/warn "Failed to apply reflection damage:" (ex-message e))
      [])))

(defn forge-entity-damage []
  (reify ped/IEntityDamage
    (apply-direct-damage! [_ world-id entity-uuid damage source-type]
      (apply-direct-damage-impl! world-id entity-uuid damage source-type))
    (apply-aoe-damage! [_ world-id x y z radius damage source-type falloff?]
      (apply-aoe-damage-impl! world-id x y z radius damage source-type falloff?))
    (apply-reflection-damage! [_ world-id entity-uuid damage source-type reflection-count max-reflections]
      (apply-reflection-damage-impl! world-id entity-uuid damage source-type reflection-count max-reflections))))

(defn install-entity-damage! []
  (alter-var-root #'ped/*entity-damage*
                  (constantly (forge-entity-damage)))
  (log/info "Forge entity damage installed"))

