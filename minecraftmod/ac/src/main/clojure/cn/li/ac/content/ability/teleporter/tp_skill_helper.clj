(ns cn.li.ac.content.ability.teleporter.tp-skill-helper
  "Shared utility functions for Teleporter category skills.

  Centralizes: skill-exp lookup, raycast helpers, entity damage wrappers,
  and common balance formulas.

  No Minecraft imports."
  (:require [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]
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
  (double
    (if-let [state (ps/get-player-state player-id)]
      (adata/get-skill-exp (:ability-data state) skill-kw)
      0.0)))

(def ^:private crit-rates [1.3 1.6 2.6])

(defn- learned?
  [player-id skill-id]
  (boolean
    (when-let [state (ps/get-player-state player-id)]
      (adata/is-learned? (:ability-data state) skill-id))))

(defn- try-lerp
  [a b l]
  (if (neg? l)
    0.0
    (+ a (* l (- b a)))))

(defn- passive-exp
  [player-id skill-id]
  (if (learned? player-id skill-id)
    (skill-exp player-id skill-id)
    -1.0))

(defn- crit-probability
  [player-id level]
  (let [dim-exp (passive-exp player-id :dim-folding-theorem)
        space-exp (passive-exp player-id :space-fluct)]
    (case level
      0 (+ (try-lerp 0.1 0.2 dim-exp)
           (try-lerp 0.18 0.25 space-exp))
      1 (try-lerp 0.10 0.15 space-exp)
      2 (try-lerp 0.01 0.03 space-exp)
      0.0)))

(defn- roll-crit-level
  [player-id]
  (some (fn [lvl]
          (when (< (rand) (crit-probability player-id lvl))
            lvl))
        [0 1 2]))

(defn- apply-teleporter-crit
  [player-id base-damage]
  (if-let [crit-level (roll-crit-level player-id)]
    (let [scaled (* base-damage (nth crit-rates crit-level 1.0))]
      (skill-effects/add-skill-exp! player-id :dim-folding-theorem (* 0.005 (inc crit-level)))
      (skill-effects/add-skill-exp! player-id :space-fluct 0.0001)
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
