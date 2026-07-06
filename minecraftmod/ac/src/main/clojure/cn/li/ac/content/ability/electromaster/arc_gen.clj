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
  (:require [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.server.platform-bridge :as server-bridge]))

(def-skill-config-ops :arc-gen)
(def ^:private arc-gen-skill-id :arc-gen)
(def ^:private fish-item-id "minecraft:cooked_cod")

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- try-ignite-block!
  "Attempt to ignite block at position with given probability."
  [world-id x y z probability]
  (when (and (block-manip/available?)
             (< (rand) probability))
    (let [current-block (block-manip/get-block*
                                                world-id x (inc y) z)]
      (when (or (nil? current-block)
                (= current-block "minecraft:air"))
        (block-manip/set-block!*
                                world-id x (inc y) z
                                "minecraft:fire")))))

(defn- try-fishing!
  "Spawn cooked fish item entity at the water position matching original
  ArcGenContext.s_perform: world.spawnEntity(new EntityItem(world, x, y, z, new ItemStack(Items.COOKED_FISH)))"
  [world-id x y z probability player]
  (when (and (block-manip/available?)
             (< (rand) probability))
    (when (block-manip/liquid-block?*
                                     world-id x y z)
      (when-let [fish-stack (pitem/create-item-stack-by-id fish-item-id 1)]
        ;; Spawn item entity in world at hit position (matching original EntityItem spawn).
        ;; Uses requiring-resolve to keep ac layer free of MC imports.
        (if (server-bridge/server-bridge-available?)
          (server-bridge/spawn-item-stack-at! player world-id x y z fish-stack)
          ;; Fallback: give directly to player if world spawn unavailable
          (entity/player-give-item-stack! player fish-stack))
        (log/debug "Arc Gen: fishing reward spawned at" x y z)))))

(defn- normal-block?
  "Check if block at position is a 'normal' solid block matching original
  BlockSelectors.filNormal: non-tile-entity, non-air, full cube."
  [world-id x y z]
  (try
    (when (block-manip/available?)
      (let [block-id (block-manip/get-block* world-id x y z)]
        (and block-id
             (not= block-id "minecraft:air")
             (not= block-id "minecraft:cave_air")
             (not= block-id "minecraft:void_air")
             ;; Skip known tile-entity / non-solid blocks (matching filNormal)
             (not (some #(= block-id %) ["minecraft:water" "minecraft:lava"
                                         "minecraft:grass" "minecraft:tall_grass"
                                         "minecraft:dead_bush" "minecraft:fern"])))))
    (catch Exception _ true)))  ;; conservative: allow on error

;; ---------------------------------------------------------------------------
;; Action
;; ---------------------------------------------------------------------------

(defn- perform-arc-gen!
  [ctx-id player-id _skill-id exp _cost-ok? _hold-ticks _cost-stage player]
  (try
    (let [damage        (cfg-lerp :combat.damage exp)
          range         (cfg-lerp :targeting.range exp)
          ignite-prob   (cfg-lerp :effect.ignite-probability exp)
          fish-prob     (if (> exp (cfg-double :effect.fishing-exp-threshold))
                          (skill-config/probability arc-gen-skill-id :effect.fishing-probability)
                          0.0)
          world-id      (geom/world-id-of player-id)
          eye           (geom/eye-pos player-id)
          look-vec      (when (raycast/available?)
                          (raycast/get-player-look-vector* player-id))]
      (when look-vec
        (let [hit-result (when (raycast/available?)
                           (raycast/raycast-combined*
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

          (fx/send! ctx-id {:topic :arc-gen/fx-perform :mode :perform} nil
                    {:start eye
                     :end   (or hit-pos
                                (geom/v+ eye (geom/v* look-vec range)))
                     :hit-type hit-type})

          (cond
            (= hit-type :entity)
            (let [entity-uuid (:entity-uuid hit-result)]
              (when (and (entity-damage/available?) entity-uuid)
                (entity-damage/apply-direct-damage!*
                                                    world-id
                                                    entity-uuid
                                                    damage
                                                    :lightning)
                (skill-effects/add-skill-exp! player-id arc-gen-skill-id
                                              (cfg-progression :progression.exp-entity exp))))

            (= hit-type :block)
            (let [block-x (int (:block-x hit-result))
                  block-y (int (:block-y hit-result))
                  block-z (int (:block-z hit-result))]
              (if (and (block-manip/available?)
                   (block-manip/liquid-block?*
                                world-id block-x block-y block-z))
              (try-fishing! world-id block-x block-y block-z fish-prob player)
              ;; Only ignite normal solid blocks (matching original BlockSelectors.filNormal filter)
              (when (normal-block? world-id block-x block-y block-z)
                (try-ignite-block! world-id block-x block-y block-z ignite-prob)))
              (skill-effects/add-skill-exp! player-id arc-gen-skill-id
                                            (cfg-progression :progression.exp-block exp)))

            :else
            nil))))
    (catch Exception e
      (log/warn "Arc Gen perform! failed:" (ex-message e)))))

(defn arc-gen-perform!
  [& args]
  (apply perform-arc-gen! args))

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
  :cost           {:down {:cp       (fn [player-id _skill-id exp]
                                      (cfg-lerp :cost.down.cp exp))
                          :overload (fn [player-id _skill-id exp]
                                      (cfg-lerp :cost.down.overload exp))}}
  :cooldown-ticks (fn [player-id _skill-id exp]
                    (skill-config/lerp-int arc-gen-skill-id :cooldown.ticks exp))
  :actions        {:perform! arc-gen-perform!}
  :prerequisites  [])

