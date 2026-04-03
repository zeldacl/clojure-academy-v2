(ns cn.li.forge1201.ability.entity-damage
  "Forge implementation of IEntityDamage protocol."
  (:require [cn.li.mcmod.platform.entity-damage :as ped]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.world.entity LivingEntity]
           [net.minecraft.world.damagesource DamageSource DamageSources]
           [net.minecraft.world.phys AABB Vec3]
           [net.minecraftforge.server ServerLifecycleHooks]
           [java.util UUID]))

(set! *warn-on-reflection* true)

(defn- get-server ^MinecraftServer []
  (ServerLifecycleHooks/getCurrentServer))

(defn- get-entity-by-uuid [^ServerLevel level uuid-str]
  (try
    (let [uuid (UUID/fromString uuid-str)]
      (.getEntity level uuid))
    (catch Exception e
      (log/warn "Failed to get entity by UUID:" uuid-str (ex-message e))
      nil)))

(defn- get-damage-source [^ServerLevel level source-type]
  (let [^DamageSources sources (.damageSources level)]
    (case source-type
      :magic (.magic sources)
      :lightning (.lightningBolt sources)
      :explosion (.explosion sources)
      :generic (.generic sources)
      (.generic sources))))

(defn- apply-direct-damage-impl! [world-id entity-uuid damage source-type]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (when-let [^ServerLevel level (.overworld server)]
        (when-let [entity (get-entity-by-uuid level entity-uuid)]
          (when (instance? LivingEntity entity)
            (let [^LivingEntity living entity
                  ^DamageSource dmg-source (get-damage-source level source-type)]
              (.hurt living dmg-source (float damage))
              true)))))
    (catch Exception e
      (log/warn "Failed to apply direct damage:" (ex-message e))
      false)))

(defn- distance-3d [x1 y1 z1 x2 y2 z2]
  (Math/sqrt (+ (* (- x2 x1) (- x2 x1))
                (* (- y2 y1) (- y2 y1))
                (* (- z2 z1) (- z2 z1)))))

(defn- apply-aoe-damage-impl! [world-id x y z radius damage source-type falloff?]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (when-let [^ServerLevel level (.overworld server)]
        (let [aabb (AABB. (- x radius) (- y radius) (- z radius)
                          (+ x radius) (+ y radius) (+ z radius))
              entities (.getEntitiesOfClass level LivingEntity aabb)
              ^DamageSource dmg-source (get-damage-source level source-type)
              damaged (atom [])]
          (doseq [^LivingEntity entity entities]
            (let [pos (.position entity)
                  dist (distance-3d x y z (.x pos) (.y pos) (.z pos))]
              (when (<= dist radius)
                (let [actual-damage (if falloff?
                                      (* damage (max 0.0 (- 1.0 (/ dist radius))))
                                      damage)]
                  (when (> actual-damage 0.0)
                    (.hurt entity dmg-source (float actual-damage))
                    (swap! damaged conj (str (.getUUID entity))))))))
          @damaged)))
    (catch Exception e
      (log/warn "Failed to apply AOE damage:" (ex-message e))
      [])))

(defn- find-nearest-living-entity [^ServerLevel level ^LivingEntity exclude x y z max-radius]
  (let [aabb (AABB. (- x max-radius) (- y max-radius) (- z max-radius)
                    (+ x max-radius) (+ y max-radius) (+ z max-radius))
        entities (.getEntitiesOfClass level LivingEntity aabb)]
    (->> entities
         (filter #(not= (.getUUID ^LivingEntity %) (.getUUID exclude)))
         (map (fn [^LivingEntity e]
                (let [pos (.position e)
                      dist (distance-3d x y z (.x pos) (.y pos) (.z pos))]
                  [e dist])))
         (filter (fn [[_ dist]] (<= dist max-radius)))
         (sort-by second)
         (first)
         (first))))

(defn- apply-reflection-damage-impl! [world-id entity-uuid damage source-type reflection-count max-reflections]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (when-let [^ServerLevel level (.overworld server)]
        (when-let [entity (get-entity-by-uuid level entity-uuid)]
          (when (instance? LivingEntity entity)
            (let [^LivingEntity living entity
                  ^DamageSource dmg-source (get-damage-source level source-type)
                  hit-entities (atom [(str (.getUUID living))])]
              ;; Apply damage to initial target
              (.hurt living dmg-source (float damage))

              ;; Handle reflections
              (loop [current-entity living
                     current-damage damage
                     reflection-num reflection-count]
                (when (< reflection-num max-reflections)
                  (let [pos (.position current-entity)
                        next-entity (find-nearest-living-entity level current-entity
                                                                 (.x pos) (.y pos) (.z pos)
                                                                 10.0)]
                    (when next-entity
                      (let [reflected-damage (* current-damage 0.5)]
                        (.hurt ^LivingEntity next-entity dmg-source (float reflected-damage))
                        (swap! hit-entities conj (str (.getUUID ^LivingEntity next-entity)))
                        (recur next-entity reflected-damage (inc reflection-num)))))))

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
