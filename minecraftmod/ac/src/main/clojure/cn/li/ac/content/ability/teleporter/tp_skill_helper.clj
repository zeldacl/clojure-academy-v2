(ns cn.li.ac.content.ability.teleporter.tp-skill-helper
  "Shared utility functions for Teleporter category skills.

  Centralizes: skill-exp lookup, raycast helpers, entity damage wrappers,
  and common balance formulas.

  No Minecraft imports."
  (:require [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.mcmod.platform.player-feedback :as player-feedback]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.util.log :as log]))

(def ^:private teleporter-critical-hit-message-key "ability.teleporter.critical_hit")

(defn- crit-rate-label
  [crit-rate]
  (format "x%.1f" (double crit-rate)))

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

(defn- calc-teleporter-crit
  [player-id base-damage]
  (if-let [crit-level (roll-crit-level player-id)]
    (let [crit-rates (cfg-double-list :dim-folding-theorem :critical.damage-multipliers)
          crit-rate (double (nth crit-rates crit-level 1.0))
          final-damage (* (double base-damage) crit-rate)
          events (cond-> ["teleporter.critical_attack"]
                   (= crit-level 2) (conj "teleporter.mastery"))]
      {:damage-before (double base-damage)
       :damage-after (double final-damage)
       :crit-level crit-level
       :crit-rate crit-rate
       :critical? true
       :message-key teleporter-critical-hit-message-key
       :message-args [(crit-rate-label crit-rate)]
       :events events})
    {:damage-before (double base-damage)
     :damage-after (double base-damage)
     :crit-level nil
     :crit-rate 1.0
     :critical? false
     :message-key nil
     :message-args []
     :events []}))

(defn crit-applied?
  "Return true when a teleporter crit both rolled and successfully applied damage."
  [damage-result]
  (boolean (and (:critical? damage-result)
                (:applied? damage-result))))

(defn- apply-teleporter-crit-side-effects!
  [player-id crit-level events {:keys [message-key message-args]}]
  (skill-effects/add-skill-exp! player-id :dim-folding-theorem
                                (* (cfg-double :dim-folding-theorem :progression.exp-per-crit-level)
                                   (inc crit-level)))
  (skill-effects/add-skill-exp! player-id :space-fluct
                                (cfg-double :space-fluct :progression.exp-critical))
  (when message-key
    (player-feedback/send-chat-message! player-id message-key message-args true))
  (doseq [event-id events]
    (ach-dispatcher/trigger-custom-event! player-id event-id)))

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

(defn reset-fall-damage!
  "Reset player's fall damage state. Returns true on success."
  [player-id]
  (when teleportation/*teleportation*
    (teleportation/reset-fall-damage! teleportation/*teleportation* player-id)))

(defn raycast-combined
  "Raycast from world position and direction, returning first hit map or nil."
  [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (when raycast/*raycast*
    (raycast/raycast-combined raycast/*raycast*
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
  (when raycast/*raycast*
    (raycast/raycast-blocks raycast/*raycast*
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
   (let [crit-result (calc-teleporter-crit attacker-id (double damage))
         applied? (deal-magic-damage! world-id entity-uuid (:damage-after crit-result))]
     (when (and applied? (:critical? crit-result))
       (apply-teleporter-crit-side-effects!
         attacker-id
         (:crit-level crit-result)
         (:events crit-result)
         crit-result))
     (assoc crit-result :applied? (boolean applied?)))))

;; ---------------------------------------------------------------------------
;; Look direction helper
;; ---------------------------------------------------------------------------

(defn player-look-vec [player-id]
  (when raycast/*raycast*
    (raycast/get-player-look-vector raycast/*raycast* player-id)))

(defn player-position [player-id]
  (when teleportation/*teleportation*
    (teleportation/get-player-position teleportation/*teleportation* player-id)))
