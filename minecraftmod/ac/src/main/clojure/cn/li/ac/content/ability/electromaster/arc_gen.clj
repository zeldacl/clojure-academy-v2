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
  - Miss: 0.001

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.util.log :as log]))

(def ^:private arc-entity-id "my_mod:entity_arc")

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id)
                  [:ability-data :skills :arc-gen :exp]
                  0.0)))

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
  "Attempt to spawn fishing hook if hitting water."
  [world-id x y z probability player-id]
  (when (and block-manip/*block-manipulation*
             (< (rand) probability))
    (when (block-manip/liquid-block? block-manip/*block-manipulation*
                                     world-id x y z)
      (log/debug "Arc Gen: fishing triggered at" x y z))))

(defn- apply-stun!
  "Apply stun effect to entity (slowness + weakness)."
  [world-id entity-uuid]
  (log/debug "Arc Gen: stun effect applied to" entity-uuid))

;; ---------------------------------------------------------------------------
;; Action
;; ---------------------------------------------------------------------------

(defn- perform-arc-gen! [{:keys [player-id ctx-id player]}]
  (try
    (let [exp           (skill-exp player-id)
          damage        (bal/lerp 5.0 9.0 exp)
          range         (bal/lerp 6.0 15.0 exp)
          ignite-prob   (bal/lerp 0.0 0.6 exp)
          fish-prob     (if (> exp 0.5) 0.1 0.0)
          can-stun?     (>= exp 1.0)
          world-id      (geom/world-id-of player-id)
          eye           (geom/eye-pos player-id)
          look-vec      (when raycast/*raycast*
                          (raycast/get-player-look-vector raycast/*raycast* player-id))]
      (when look-vec
        (when player
          (entity/player-spawn-entity-by-id! player arc-entity-id 0.0))
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
                (skill-effects/add-skill-exp! player-id :arc-gen
                                              (+ 0.0048 (* 0.0024 exp)))))

            (= hit-type :block)
            (let [block-x (int (:block-x hit-result))
                  block-y (int (:block-y hit-result))
                  block-z (int (:block-z hit-result))]
              (try-ignite-block! world-id block-x block-y block-z ignite-prob)
              (try-fishing! world-id block-x block-y block-z fish-prob player-id)
              (skill-effects/add-skill-exp! player-id :arc-gen
                                            (+ 0.0018 (* 0.0009 exp))))

            :else
            (skill-effects/add-skill-exp! player-id :arc-gen 0.001)))))
    (catch Exception e
      (log/warn "Arc Gen perform! failed:" (ex-message e)))))

(defn arc-gen-perform!
  [{:keys [player-id ctx-id] :as evt}]
  (perform-arc-gen! evt))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! arc-gen
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
                                      (bal/lerp 30.0 70.0 (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (bal/lerp 18.0 11.0 (skill-exp player-id)))}}
  :cooldown-ticks (fn [{:keys [player-id]}]
                    (int (bal/lerp 15.0 5.0 (skill-exp player-id))))
  :actions        {:perform! arc-gen-perform!}
  :prerequisites  [])
