(ns cn.li.mc1201.runtime.adapter.entity-damage
  "Shared IEntityDamage adapter factory.

  Platform namespaces provide only server lookup and platform-specific entity
  query callbacks; this namespace owns the damage-flow orchestration."
  (:require [cn.li.mc1201.runtime.entity-damage-core :as core]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.entity-damage :as damage-effects]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.world.entity LivingEntity]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.phys AABB]))

(defn- resolve-level* [server resolve-level-fn world-id]
  (when server
    (resolve-level-fn server world-id)))

(defn- make-aabb [x y z radius]
  (AABB. (- x radius) (- y radius) (- z radius)
         (+ x radius) (+ y radius) (+ z radius)))

(defn- apply-vanilla-hurt!
  [^LivingEntity entity dmg-source damage]
  (.hurt entity dmg-source (float damage)))

(defn- pvp-blocked?
  "True when `entity` is a player and the content-registered PvP gate
   (mcmod.platform.entity-damage/install-pvp-gate!) currently disallows it —
   matches upstream AbilityContext.dealDamage's
   (canAttackPlayer() || !(target instanceof EntityPlayer)) check."
  [entity]
  (and (instance? Player entity) (not (damage-effects/pvp-allowed?))))

(defn create-entity-damage
  "Return a function map implementing the entity-damage contract.

   Keys: :apply-direct-damage! :apply-aoe-damage! :apply-reflection-damage!"
  [server-fn {:keys [resolve-level-fn get-entity-by-uuid-fn get-living-entities-in-aabb-fn living-entity?-fn apply-hurt-fn]
              :or {resolve-level-fn query-core/resolve-level
                   get-entity-by-uuid-fn query-core/get-entity-by-uuid
                   living-entity?-fn (fn [entity] (instance? LivingEntity entity))
                   apply-hurt-fn apply-vanilla-hurt!}}]
  (let [get-entity-by-uuid (or get-entity-by-uuid-fn query-core/get-entity-by-uuid)
        get-living-entities-in-aabb (or get-living-entities-in-aabb-fn (fn [_level _aabb] []))
        apply-hurt! (or apply-hurt-fn apply-vanilla-hurt!)
        living? (or living-entity?-fn (fn [entity] (instance? LivingEntity entity)))
        resolve-level (fn [world-id]
                        (when-let [^MinecraftServer server (server-fn)]
                          (resolve-level* server resolve-level-fn world-id)))]
    {:apply-direct-damage!
     (fn [world-id entity-uuid damage source-type]
       (try
         (if-let [^ServerLevel level (resolve-level world-id)]
           (if-let [entity (get-entity-by-uuid level entity-uuid)]
             (if (and (living? entity) (not (pvp-blocked? entity)))
               (let [^LivingEntity living entity
                     dmg-source (core/resolve-damage-source level source-type)]
                 (apply-hurt! living dmg-source (float damage))
                 true)
               false)
             false)
           false)
         (catch Exception e
           (log/warn "Failed to apply direct damage:" (ex-message e))
           false)))

     :apply-aoe-damage!
     (fn [world-id x y z radius damage source-type falloff?]
       (try
         (if-let [^ServerLevel level (resolve-level world-id)]
           (let [origin-pos {:x x :y y :z z}
                 aabb (make-aabb x y z radius)
                 entities (or (get-living-entities-in-aabb level aabb) [])
                 dmg-source (core/resolve-damage-source level source-type)]
             (core/apply-aoe-damage-flow!
               entities origin-pos radius damage falloff?
               (fn [^LivingEntity entity actual-damage]
                 (when-not (pvp-blocked? entity)
                   (apply-hurt! entity dmg-source (float actual-damage))))))
           [])
         (catch Exception e
           (log/warn "Failed to apply AOE damage:" (ex-message e))
           [])))

     :apply-reflection-damage!
     (fn [world-id entity-uuid damage source-type reflection-count max-reflections]
       (try
         (if-let [^ServerLevel level (resolve-level world-id)]
           (if-let [entity (get-entity-by-uuid level entity-uuid)]
             (if (living? entity)
               (let [^LivingEntity living entity
                     dmg-source (core/resolve-damage-source level source-type)
                     search-radius (core/reflection-search-radius)]
                 (core/apply-reflection-damage-flow!
                   living damage reflection-count max-reflections search-radius
                   (fn [^LivingEntity current-entity radius]
                     (let [current-pos (core/entity-pos-map current-entity)
                           aabb (make-aabb (:x current-pos) (:y current-pos) (:z current-pos) radius)
                           candidates (mapv core/candidate-map (or (get-living-entities-in-aabb level aabb) []))
                           target-uuid (core/select-reflection-target-uuid
                                         (str (.getUUID current-entity))
                                         current-pos candidates radius)]
                       (when target-uuid
                         (get-entity-by-uuid level target-uuid))))
                   (fn [^LivingEntity target damage-value]
                     (when-not (pvp-blocked? target)
                       (apply-hurt! target dmg-source (float damage-value))))))
               [])
             [])
           [])
         (catch Exception e
           (log/warn "Failed to apply reflection damage:" (ex-message e))
           [])))}))

(defn install-entity-damage!
  [entity-damage label]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/install-adapter! fw-atom :entity-damage entity-damage))
  nil)
