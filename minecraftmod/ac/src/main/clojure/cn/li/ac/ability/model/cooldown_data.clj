(ns cn.li.ac.ability.model.cooldown-data
  "Pure-data functions for CooldownData.

  Cooldown key = [ctrl-id sub-id] (both keywords).
  Main cooldown uses sub-id :main (equivalent to old subID=0).
  Sub-cooldowns use user-defined keywords.

  Map schema: {[ctrl-id sub-id] remaining-ticks}"
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn new-cooldown-data [] {})

;; ============================================================================
;; Queries
;; ============================================================================

(defn in-cooldown?
  [d ctrl-id sub-id]
  (let [rem (get d [ctrl-id sub-id] 0)]
    (pos? rem)))

(defn get-remaining
  [d ctrl-id sub-id]
  (get d [ctrl-id sub-id] 0))

;; ============================================================================
;; Mutation (pure, returns new map)
;; ============================================================================

(defn set-cooldown
  "Set cooldown to max(existing, ticks). Cooldowns never decrease."
  [d ctrl-id sub-id ticks]
  (let [existing (get d [ctrl-id sub-id] 0)
        value    (max existing ticks)]
    (assoc d [ctrl-id sub-id] value)))

(defn tick-cooldowns
  "Decrement all cooldowns by 1; remove entries at 0."
  [d]
  (->> d
       (keep (fn [[k v]]
               (let [nv (dec v)]
                 (when (pos? nv) [k nv]))))
       (into {})))

;; ============================================================================
;; Serialization helpers
;; ============================================================================

(defn cooldown-data->vec
  "Serialize to vector of [cat-key ctrl-key sub-key ticks] for NBT."
  [d]
  (mapv (fn [[[ctrl-id sub-id] ticks]]
          [ctrl-id sub-id ticks])
        d))

(defn vec->cooldown-data
  "Deserialize from vector produced by cooldown-data->vec."
  [coll]
  (->> coll
       (reduce (fn [acc [ctrl-id sub-id ticks]]
                 (assoc acc [(keyword ctrl-id) (keyword sub-id)] (int ticks)))
               {})))
