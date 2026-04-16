(ns cn.li.forge1201.runtime.entity-damage
  "Forge implementation of IEntityDamage protocol."
  (:require [cn.li.mcmod.platform.entity-damage :as ped]
            [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [cn.li.forge1201.bridge ForgeRuntimeBridge]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.world.entity Entity LivingEntity]
           [net.minecraft.world.damagesource DamageSource]
           [net.minecraft.world.phys AABB Vec3]
           [net.minecraftforge.server ServerLifecycleHooks]
           [java.util UUID]))

(defn- get-server ^MinecraftServer []
  (ServerLifecycleHooks/getCurrentServer))

(defn- get-entity-by-uuid [^ServerLevel level uuid-str]
  (try
    (let [uuid (UUID/fromString uuid-str)]
      (.getEntity level uuid))
    (catch Exception e
      (log/warn "Failed to get entity by UUID:" uuid-str (ex-message e))
      nil)))

(defn- get-damage-source ^DamageSource [^ServerLevel level source-type]
  ;; DamageSources is bootstrap-sensitive; access via reflection at runtime
  (try
    (let [sources-method (.getMethod (class level) "damageSources" (into-array Class []))
          ^Object sources (.invoke sources-method level (object-array []))
          sources-class (class sources)
          method-name (case source-type
                        :magic "magic"
                        :lightning "lightningBolt"
                        :explosion "explosion"
                        :generic "generic"
                        "generic")
          dam-method (.getMethod sources-class method-name (into-array Class []))]
      (.invoke dam-method sources (object-array [])))
    (catch Exception e
      (log/warn "Failed to get damage source:" source-type (ex-message e))
      nil)))

(defn- entity-pos-map [^LivingEntity entity]
  (let [^Vec3 pos (.position entity)]
    {:x (.x pos)
     :y (.y pos)
     :z (.z pos)}))

(defn- candidate-map [^LivingEntity entity]
  (let [pos (entity-pos-map entity)]
    {:entity-uuid (str (.getUUID entity))
     :x (:x pos)
     :y (:y pos)
     :z (:z pos)}))

(defn- apply-direct-damage-impl! [_world-id entity-uuid damage source-type]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (when-let [^ServerLevel level (.overworld server)]
        (when-let [entity (get-entity-by-uuid level entity-uuid)]
          (when (ForgeRuntimeBridge/isLivingEntity ^Entity entity)
            (let [^LivingEntity living entity
                  ^DamageSource dmg-source (get-damage-source level source-type)]
              (.hurt living dmg-source (float damage))
              true)))))
    (catch Exception e
      (log/warn "Failed to apply direct damage:" (ex-message e))
      false)))

(defn- apply-aoe-damage-impl! [_world-id x y z radius damage source-type falloff?]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (when-let [^ServerLevel level (.overworld server)]
        (let [origin-pos {:x x :y y :z z}
              aabb (AABB. (- x radius) (- y radius) (- z radius)
                          (+ x radius) (+ y radius) (+ z radius))
              entities (ForgeRuntimeBridge/getLivingEntitiesInAabb level aabb)
              ^DamageSource dmg-source (get-damage-source level source-type)
              damaged (atom [])]
          (doseq [^LivingEntity entity entities]
            (let [target-pos (entity-pos-map entity)
                  actual-damage (ability-runtime/compute-aoe-damage
                                  origin-pos target-pos radius damage falloff?)]
              (when (> actual-damage 0.0)
                (.hurt entity dmg-source (float actual-damage))
                (swap! damaged conj (str (.getUUID entity))))))
          @damaged)))
    (catch Exception e
      (log/warn "Failed to apply AOE damage:" (ex-message e))
      [])))

(defn- find-reflection-target [^ServerLevel level ^LivingEntity current-entity max-radius]
  (let [current-pos (entity-pos-map current-entity)
        aabb (AABB. (- (:x current-pos) max-radius)
                    (- (:y current-pos) max-radius)
                    (- (:z current-pos) max-radius)
                    (+ (:x current-pos) max-radius)
                    (+ (:y current-pos) max-radius)
                    (+ (:z current-pos) max-radius))
        candidates (mapv candidate-map (ForgeRuntimeBridge/getLivingEntitiesInAabb level aabb))
        target-uuid (ability-runtime/select-reflection-target
                      (str (.getUUID current-entity))
                      current-pos
                      candidates
                      max-radius)]
    (when target-uuid
      (get-entity-by-uuid level target-uuid))))

(defn- apply-reflection-damage-impl! [_world-id entity-uuid damage source-type reflection-count max-reflections]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (when-let [^ServerLevel level (.overworld server)]
        (when-let [entity (get-entity-by-uuid level entity-uuid)]
          (when (ForgeRuntimeBridge/isLivingEntity ^Entity entity)
            (let [^LivingEntity living entity
                  ^DamageSource dmg-source (get-damage-source level source-type)
                  search-radius (double (ability-runtime/get-reflection-search-radius))
                  hit-entities (atom [(str (.getUUID living))])]
              (.hurt living dmg-source (float damage))
              (loop [^LivingEntity current-entity living
                     current-damage damage
                     reflection-num reflection-count]
                (when (< reflection-num max-reflections)
                  (when-let [^LivingEntity next-entity (find-reflection-target level current-entity search-radius)]
                    (let [reflected-damage (ability-runtime/compute-reflected-damage current-damage)]
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
