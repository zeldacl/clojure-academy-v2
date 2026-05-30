(ns cn.li.ac.content.ability.electromaster.arc-gen
  "Arc Gen skill - instant electric arc attack with raycast targeting.

  Pattern: :instant (single key press)
  Cost: CP lerp(30, 70, exp), overload lerp(18, 11, exp)
  Cooldown: lerp(15, 5, exp) ticks
  Damage: lerp(5, 9, exp)
  Range: lerp(6, 15, exp) blocks
  Ignite probability: lerp(0, 0.6, exp)
  Fishing probability: 0.1 if exp > 0.5, else 0
  Stun: if exp >= 1.0

  Exp gain:
  - Hit entity: 0.0048 + 0.0024 * exp
  - Hit block: 0.0018 + 0.0009 * exp
  - Miss: none

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]
            [cn.li.mcmod.util.log :as log]))

(def ^:private arc-gen-skill-id :arc-gen)
(def ^:private fish-item-id "minecraft:cooked_cod")

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id arc-gen-skill-id))

(defn- cfg-double [field-id]
  (skill-config/tunable-double arc-gen-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double arc-gen-skill-id field-id exp))

(defn- cfg-progression [field-id exp]
  (let [[base scale] (skill-config/tunable-double-list arc-gen-skill-id field-id)]
    (+ (double base) (* (double scale) (double exp)))))

(defn- try-ignite-block!
  "Attempt to ignite block at position with given probability."
  [world-id x y z probability]
  (when (and block-manip/*block-manipulation*
             (< (rand) probability))
    (let [current-block (block-manip/get-block block-manip/*block-manipulation*
                                                world-id x (inc y) z)]
      (when (or (nil? current-block)
                (= current-block "minecraft:air"))
        (block-manip/set-block! block-manip/*block-manipulation*
                                world-id x (inc y) z
                                "minecraft:fire")))))

(defn- try-fishing!
  "Attempt to reward cooked fish when Arc-Gen strikes water."
  [world-id x y z probability player]
  (when (and block-manip/*block-manipulation*
             (< (rand) probability))
    (when (block-manip/liquid-block? block-manip/*block-manipulation*
                                     world-id x y z)
      (when-let [fish-stack (pitem/create-item-stack-by-id fish-item-id 1)]
        (entity/player-give-item-stack! player fish-stack)
        (log/debug "Arc Gen: fishing reward granted at" x y z)))))

(defn- apply-stun!
  "Apply stun effect to entity (slowness + weakness)."
  [_world-id entity-uuid]
  (when (and potion-effects/*potion-effects* entity-uuid)
    (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                         entity-uuid :slowness 40 1)
    (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                         entity-uuid :weakness 40 1)
    (log/debug "Arc Gen: stun effect applied to" entity-uuid)))

;; ---------------------------------------------------------------------------
;; Action
;; ---------------------------------------------------------------------------

(defn- perform-arc-gen! [{:keys [player-id ctx-id player]}]
  (try
    (let [exp           (skill-exp player-id)
          damage        (cfg-lerp :combat.damage exp)
          range         (cfg-lerp :targeting.range exp)
          ignite-prob   (cfg-lerp :effect.ignite-probability exp)
          fish-prob     (if (> exp (cfg-double :effect.fishing-exp-threshold))
                          (skill-config/probability arc-gen-skill-id :effect.fishing-probability)
                          0.0)
          can-stun?     (>= exp (cfg-double :effect.stun-exp-threshold))
          world-id      (geom/world-id-of player-id)
          eye           (geom/eye-pos player-id)
          look-vec      (when raycast/*raycast*
                          (raycast/get-player-look-vector raycast/*raycast* player-id))]
      (when look-vec
        (let [hit-result (when raycast/*raycast*
                           (raycast/raycast-combined raycast/*raycast*
                                                     world-id
                                                     (:x eye) (:y eye) (:z eye)
                                                     (double (:x look-vec))
                                                     (double (:y look-vec))
                                                     (double (:z look-vec))
                                                     (double range)))
              hit-type   (:type hit-result)
              hit-pos    (when hit-result
                           {:x (:x hit-result)
                            :y (:y hit-result)
                            :z (:z hit-result)})]

          (ctx/ctx-send-to-client! ctx-id :arc-gen/fx-perform
                                   {:start eye
                                    :end   (or hit-pos
                                               (geom/v+ eye (geom/v* look-vec range)))
                                    :hit-type hit-type})

          (cond
            (= hit-type :entity)
            (let [entity-uuid (:entity-uuid hit-result)]
              (when (and entity-damage/*entity-damage* entity-uuid)
                (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                    world-id
                                                    entity-uuid
                                                    damage
                                                    :lightning)
                (when can-stun?
                  (apply-stun! world-id entity-uuid))
                (skill-effects/add-skill-exp! player-id arc-gen-skill-id
                                              (cfg-progression :progression.exp-entity exp))))

            (= hit-type :block)
            (let [block-x (int (:block-x hit-result))
                  block-y (int (:block-y hit-result))
                  block-z (int (:block-z hit-result))]
              (if (and block-manip/*block-manipulation*
                   (block-manip/liquid-block? block-manip/*block-manipulation*
                                world-id block-x block-y block-z))
              (try-fishing! world-id block-x block-y block-z fish-prob player)
              (try-ignite-block! world-id block-x block-y block-z ignite-prob))
              (skill-effects/add-skill-exp! player-id arc-gen-skill-id
                                            (cfg-progression :progression.exp-block exp)))

            :else
            nil))))
    (catch Exception e
      (log/warn "Arc Gen perform! failed:" (ex-message e)))))

(defn arc-gen-perform!
  [evt]
  (perform-arc-gen! evt))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill arc-gen
  :id             :arc-gen
  :category-id    :electromaster
  :name-key       "ability.skill.electromaster.arc_gen"
  :description-key "ability.skill.electromaster.arc_gen.desc"
  :icon           "textures/abilities/electromaster/skills/arc_gen.png"
  :ui-position    [24 46]
  :level          1
  :controllable?  true
  :ctrl-id        :arc-gen
  :pattern        :instant
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (cfg-lerp :cost.down.cp (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (cfg-lerp :cost.down.overload (skill-exp player-id)))}}
  :cooldown-ticks (fn [{:keys [player-id]}]
                    (skill-config/lerp-int arc-gen-skill-id :cooldown.ticks (skill-exp player-id)))
  :actions        {:perform! arc-gen-perform!}
  :prerequisites  [])
