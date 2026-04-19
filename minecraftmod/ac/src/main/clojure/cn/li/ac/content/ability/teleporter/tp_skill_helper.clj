(ns cn.li.ac.content.ability.teleporter.tp-skill-helper
  "Shared utility functions for Teleporter category skills.

  Centralizes: skill-exp lookup, raycast helpers, entity damage wrappers,
  and common balance formulas.

  No Minecraft imports."
  (:require [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Exp accessors
;; ---------------------------------------------------------------------------

(defn skill-exp
  "Return current experience for skill-kw for player-id, 0.0 if missing."
  [player-id skill-kw]
  (double (get-in (ps/get-player-state player-id)
                  [:ability-data :skills skill-kw :exp]
                  0.0)))

;; ---------------------------------------------------------------------------
;; Teleport helpers
;; ---------------------------------------------------------------------------

(defn teleport-to!
  "Teleport player-id to (x y z) in world-id.
  Returns true if successful."
  [player-id world-id x y z]
  (when teleportation/*teleportation*
    (let [result (teleportation/teleport-player! teleportation/*teleportation*
                                                 player-id world-id
                                                 (double x) (double y) (double z))]
      (when result
        (teleportation/reset-fall-damage! teleportation/*teleportation* player-id))
      result)))

;; ---------------------------------------------------------------------------
;; Entity raycast helper
;; ---------------------------------------------------------------------------

(defn raycast-entity
  "Cast ray from player, returning first living non-player entity UUID, or nil."
  [player-id max-dist]
  (when raycast/*raycast*
    (when-let [result (raycast/raycast-from-player raycast/*raycast*
                                                    player-id (double max-dist) true)]
      (when (and (:hit-entity result)
                 (not= (str (:entity-uuid result)) (str player-id)))
        result))))

;; ---------------------------------------------------------------------------
;; Damage helper
;; ---------------------------------------------------------------------------

(defn deal-magic-damage!
  "Apply magic (armor-bypassing) damage to entity uuid in world."
  [world-id entity-uuid damage]
  (when entity-damage/*entity-damage*
    (entity-damage/apply-direct-damage!
      entity-damage/*entity-damage*
      world-id entity-uuid (double damage) :magic)))

;; ---------------------------------------------------------------------------
;; Look direction helper
;; ---------------------------------------------------------------------------

(defn player-look-vec [player-id]
  (when raycast/*raycast*
    (raycast/get-player-look-vector raycast/*raycast* player-id)))

(defn player-position [player-id]
  (when teleportation/*teleportation*
    (teleportation/get-player-position teleportation/*teleportation* player-id)))
