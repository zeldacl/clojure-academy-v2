(ns cn.li.mc1201.runtime.entity-damage-core
  "Loader-agnostic helpers for entity damage runtime flows.

  Keeps generic shaping/calculation logic in shared mc1201 while platform layers
  own world/entity lookup and concrete damage application calls."
  (:require [cn.li.mcmod.platform.power-runtime :as power-runtime])
  (:import [net.minecraft.world.entity LivingEntity]
           [net.minecraft.world.phys Vec3]))

(defn source-type->method-name
  [source-type]
  (case source-type
    :magic "magic"
    :lightning "lightningBolt"
    :explosion "explosion"
    :generic "generic"
    "generic"))

(defn resolve-damage-source
  "Resolve level.damageSources().<kind>() via reflection, returning nil on failure.
  Keep reflection path bootstrap-safe for both Forge/Fabric AOT/runtime contexts."
  [level source-type]
  (when level
    (try
      (let [sources-method (.getMethod (class level) "damageSources" (into-array Class []))
            ^Object sources (.invoke sources-method level (object-array []))
            sources-class (class sources)
            method-name (source-type->method-name source-type)
            dam-method (.getMethod sources-class method-name (into-array Class []))]
        (.invoke dam-method sources (object-array [])))
      (catch Exception _
        nil))))

(defn entity-pos-map
  [^LivingEntity entity]
  (let [^Vec3 pos (.position entity)]
    {:x (.x pos)
     :y (.y pos)
     :z (.z pos)}))

(defn candidate-map
  [^LivingEntity entity]
  (let [pos (entity-pos-map entity)]
    {:entity-uuid (str (.getUUID entity))
     :x (:x pos)
     :y (:y pos)
     :z (:z pos)}))

(defn compute-aoe-damage
  [origin-pos target-pos radius damage falloff?]
  (power-runtime/compute-aoe-damage origin-pos target-pos radius damage falloff?))

(defn select-reflection-target-uuid
  [current-entity-uuid current-pos candidates max-radius]
  (power-runtime/select-reflection-target
    current-entity-uuid
    current-pos
    candidates
    max-radius))

(defn compute-reflected-damage
  [current-damage]
  (power-runtime/compute-reflected-damage current-damage))
