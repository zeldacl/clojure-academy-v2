(ns cn.li.ac.content.ability.teleporter.tp-skill-helper
  "Shared utility functions for Teleporter category skills.

  Centralizes: raycast helpers, entity damage wrappers, passive crit pipeline,
  and common balance formulas.

  No Minecraft imports."
  (:require [cn.li.ac.content.ability.teleporter.passive-hooks :as passive-hooks]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.ac.ability.effects.motion :as motion-effects]
            [cn.li.mcmod.util.log :as log]))

(declare player-look-vec player-position)

(defn crit-applied?
  "Return true when a teleporter crit both rolled and successfully applied damage."
  [damage-result]
  (boolean (and (:critical? damage-result)
                (:applied? damage-result))))

;; ---------------------------------------------------------------------------
;; Teleport helpers
;; ---------------------------------------------------------------------------

(defn teleport-to!
  "Teleport player-id to (x y z) in world-id.
  Returns true if successful."
  [player-id world-id x y z]
  (when (motion-effects/teleportation-available?)
    (let [result (motion-effects/teleport-player! player-id world-id
                                                 (double x) (double y) (double z))]
      (when result
        (motion-effects/reset-fall-damage! player-id))
      result)))

(defn reset-fall-damage!
  "Reset player's fall damage state. Returns true on success."
  [player-id]
  (when (motion-effects/teleportation-available?)
    (motion-effects/reset-fall-damage! player-id)))

(defn raycast-combined
  "Raycast from world position and direction, returning first hit map or nil."
  [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (when (raycast/available?)
    (raycast/raycast-combined*
                              world-id
                              (double start-x)
                              (double start-y)
                              (double start-z)
                              (double dir-x)
                              (double dir-y)
                              (double dir-z)
                              (double max-distance))))

(defn raycast-blocks
  "Raycast blocks from world position and direction, returning block hit or nil."
  [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (when (raycast/available?)
    (raycast/raycast-blocks*
                            world-id
                            (double start-x)
                            (double start-y)
                            (double start-z)
                            (double dir-x)
                            (double dir-y)
                            (double dir-z)
                            (double max-distance))))

;; ---------------------------------------------------------------------------
;; Entity raycast helper
;; ---------------------------------------------------------------------------

(defn raycast-entity
  "Cast ray from player, returning first living non-player entity UUID, or nil."
  [player-id max-dist]
  (when (raycast/available?)
    (when-let [result (raycast/raycast-from-player*
                                                    player-id (double max-dist) true)]
      (when (and (:hit-entity result)
                 (not= (str (:entity-uuid result)) (str player-id)))
        result))))

;; ---------------------------------------------------------------------------
;; Damage helper
;; ---------------------------------------------------------------------------

(defn deal-magic-damage!
  "Apply magic (armor-bypassing) damage to entity uuid in world.
  3-arity is plain damage; 4-arity applies teleporter passive crit pipeline."
  ([world-id entity-uuid damage]
   (when (entity-damage/available?)
     (entity-damage/apply-direct-damage!*
       world-id entity-uuid (double damage) :magic)))
  ([attacker-id world-id entity-uuid damage]
   (let [crit-result (passive-hooks/calc-teleporter-crit attacker-id (double damage))
         applied? (deal-magic-damage! world-id entity-uuid (:damage-after crit-result))]
     (when (and applied? (:critical? crit-result))
       (passive-hooks/apply-crit-side-effects!
         attacker-id
         (:crit-level crit-result)
         (:events crit-result)
         crit-result))
     (assoc crit-result :applied? (boolean applied?)))))

;; ---------------------------------------------------------------------------
;; Look direction helper
;; ---------------------------------------------------------------------------

(defn player-look-vec [player-id]
  (when (raycast/available?)
    (raycast/get-player-look-vector* player-id)))

(defn player-position [player-id]
  (when (motion-effects/teleportation-available?)
    (motion-effects/player-position player-id)))

