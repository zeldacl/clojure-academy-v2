(ns cn.li.ac.content.ability.teleporter.tp-skill-helper
  "Shared utility functions for Teleporter category skills.

  Centralizes: skill-exp lookup, raycast helpers, entity damage wrappers,
  and common balance formulas.

  No Minecraft imports."
  (:require [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]
            [cn.li.ac.ability.skill-config :as skill-config]
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
  (double
    (if-let [state (skill-effects/get-player-state player-id)]
      (adata/get-skill-exp (:ability-data state) skill-kw)
      0.0)))

(defn cfg-double [skill-id field-id]
  (skill-config/tunable-double skill-id field-id))

(defn cfg-int [skill-id field-id]
  (skill-config/tunable-int skill-id field-id))

(defn cfg-lerp [skill-id field-id exp]
  (skill-config/lerp-double skill-id field-id exp))

(defn cfg-lerp-int [skill-id field-id exp]
  (skill-config/lerp-int skill-id field-id exp))

(defn cfg-probability [skill-id field-id]
  (skill-config/probability skill-id field-id))

(defn cfg-double-list [skill-id field-id]
  (skill-config/tunable-double-list skill-id field-id))

(defn- learned?
  [player-id skill-id]
  (boolean
    (when-let [state (skill-effects/get-player-state player-id)]
      (adata/is-learned? (:ability-data state) skill-id))))

(defn- try-lerp
  [a b l]
  (if (neg? l)
    0.0
    (+ a (* l (- b a)))))

(defn- passive-cfg-lerp
  [skill-id field-id exp]
  (if (neg? exp)
    0.0
    (cfg-lerp skill-id field-id exp)))

(defn- passive-exp
  [player-id skill-id]
  (if (learned? player-id skill-id)
    (skill-exp player-id skill-id)
    -1.0))

(defn- crit-probability
  [player-id level]
  (let [dim-exp (passive-exp player-id :dim-folding-theorem)
        space-exp (passive-exp player-id :space-fluct)]
    (cond
  (= level 0) (+ (passive-cfg-lerp :dim-folding-theorem :critical.level0-probability dim-exp)
         (passive-cfg-lerp :space-fluct :critical.level0-probability space-exp))
  (= level 1) (passive-cfg-lerp :space-fluct :critical.level1-probability space-exp)
  (= level 2) (passive-cfg-lerp :space-fluct :critical.level2-probability space-exp)
      :else 0.0)))

(defn- roll-crit-level
  [player-id]
  (some (fn [lvl]
          (when (< (rand) (crit-probability player-id lvl))
            lvl))
        [0 1 2]))

(defn- apply-teleporter-crit
  [player-id base-damage]
  (if-let [crit-level (roll-crit-level player-id)]
    (let [crit-rates (cfg-double-list :dim-folding-theorem :critical.damage-multipliers)
          scaled (* base-damage (nth crit-rates crit-level 1.0))]
      (skill-effects/add-skill-exp! player-id :dim-folding-theorem
                                    (* (cfg-double :dim-folding-theorem :progression.exp-per-crit-level)
                                       (inc crit-level)))
      (skill-effects/add-skill-exp! player-id :space-fluct
                                    (cfg-double :space-fluct :progression.exp-critical))
      (ach-dispatcher/trigger-custom-event! player-id "teleporter.critical_attack")
      (when (= crit-level 2)
        (ach-dispatcher/trigger-custom-event! player-id "teleporter.mastery"))
      {:damage scaled :crit-level crit-level})
    {:damage base-damage :crit-level nil}))

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
  "Apply magic (armor-bypassing) damage to entity uuid in world.
  3-arity is plain damage; 4-arity applies teleporter passive crit pipeline."
  ([world-id entity-uuid damage]
   (when entity-damage/*entity-damage*
     (entity-damage/apply-direct-damage!
       entity-damage/*entity-damage*
       world-id entity-uuid (double damage) :magic)))
  ([attacker-id world-id entity-uuid damage]
   (let [{:keys [damage]} (apply-teleporter-crit attacker-id (double damage))]
     (deal-magic-damage! world-id entity-uuid damage))))

;; ---------------------------------------------------------------------------
;; Look direction helper
;; ---------------------------------------------------------------------------

(defn player-look-vec [player-id]
  (when raycast/*raycast*
    (raycast/get-player-look-vector raycast/*raycast* player-id)))

(defn player-position [player-id]
  (when teleportation/*teleportation*
    (teleportation/get-player-position teleportation/*teleportation* player-id)))
