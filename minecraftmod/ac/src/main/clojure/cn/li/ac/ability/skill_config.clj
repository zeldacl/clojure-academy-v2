(ns cn.li.ac.ability.skill-config
  "Per-skill ability configuration descriptors and effective-spec helpers.

  This namespace is the SSoT for player-facing skill balance config. It stays
  platform-neutral: AC owns descriptors/defaults/getters, while Forge/Fabric
  expose the domains as TOML/JSON through the generic mcmod config bridge."
  (:require [clojure.string :as str]
            [cn.li.ac.ability.config-common :as ability-config-common]
            [cn.li.ac.ability.skill-config.common :as common]
            [cn.li.ac.ability.skill-config.electromaster :as electromaster]
            [cn.li.ac.ability.skill-config.meltdowner :as meltdowner]
            [cn.li.ac.ability.skill-config.teleporter :as teleporter]
            [cn.li.ac.ability.skill-config.vecmanip :as vecmanip]
            [cn.li.ac.config.common :as config-common]
            [cn.li.mcmod.config.registry :as config-reg]))

(def category-ids
  common/category-ids)

(def skill-definitions
  common/skill-definitions)

(def all-skill-ids
  common/all-skill-ids)

(def skill-definitions-by-id
  (into {} (map #(vector (get % :id) %) skill-definitions)))

(def skills-by-category
  (into {}
        (map (fn [category-id]
               [category-id (vec (filter #(= category-id (:category-id %)) skill-definitions))])
             category-ids)))

(def field-definitions
  common/field-definitions)

;; One entry per category module — adding a category means adding one entry
;; here (plus the :require above) instead of touching two separate concat
;; forms.
(def ^:private category-modules
  [{:tunable electromaster/skill-tunable-definitions :internal electromaster/internal-tunable-definitions}
   {:tunable meltdowner/skill-tunable-definitions :internal meltdowner/internal-tunable-definitions}
   {:tunable teleporter/skill-tunable-definitions :internal teleporter/internal-tunable-definitions}
   {:tunable vecmanip/skill-tunable-definitions :internal vecmanip/internal-tunable-definitions}])

(def skill-tunable-definitions
  (vec (mapcat #(get % :tunable) category-modules)))

(def ^:private internal-tunable-definitions
  (vec (mapcat #(get % :internal) category-modules)))

(def field-definitions-by-id
  (into {} (map #(vector (get % :id) %) field-definitions)))

(def skill-tunable-definitions-by-skill
  (group-by #(get % :skill-id) skill-tunable-definitions))

(def skill-tunable-definitions-by-category
  (into {}
        (map (fn [category-id]
               [category-id
                (vec (filter (fn [{:keys [skill-id]}]
                               (= category-id (get-in skill-definitions-by-id [skill-id :category-id])))
                             skill-tunable-definitions))])
             category-ids)))

(def ^:private skill-tunable-definitions-by-skill-field
  (into {}
        (map (fn [[skill-id definitions]]
               [skill-id (into {} (map #(vector (get % :id) %) definitions))])
             skill-tunable-definitions-by-skill)))

(def ^:private internal-tunable-definitions-by-skill-field
  (into {}
        (map (fn [[skill-id definitions]]
               [skill-id (into {} (map #(vector (get % :id) %) definitions))])
             (group-by #(get % :skill-id) internal-tunable-definitions))))

(defn config-key
  [skill-id field-id]
  (keyword (str (name skill-id) "." (name field-id))))

;; Combat EDN declares parameter names and types only.  These bindings live on
;; the AC/config side so the data document never reaches backwards into the
;; config registry.  The values are materialized once while the catalog is
;; loaded, before combat-core compiles the program.
(def edn-parameter-bindings
  "Bindings for the legacy schema-version-1 :parameters/:param path.
  railgun/arc-gen/thunder-clap are fully schema v2 now (see
  edn-tunable-bindings below) and no longer declare :parameters at all.
  Only vec-reflection still has an entry here, for the 5 fields its damage
  :reactions block reads (see the comment on its entry)."
  {;; vec-reflection is schema v2 (see :tunables/edn-tunable-bindings below)
   ;; except for its damage :reactions block, which is a separate subsystem
   ;; (combat_runtime's apply-combat-damage-reactions) not yet folded into the
   ;; :costs/:progression policy evaluator -- deferred, see the plan's
   ;; Phase 6. Its 5 fields still go through this legacy :parameters path
   ;; unchanged.
   :vec-reflection
   {:damage-multiplier :combat.damage-multiplier
    :min-reflected-damage :combat.min-reflected-damage
    :damage-cp :cost.damage.cp
    :exp-damage-scale :progression.exp-damage-scale
    :max-reflections :combat.max-reflections}})

(declare tunable-double tunable-int tunable-double-list tunable-string-list)

(defn- read-edn-parameter
  [skill-id parameter-id field-id type]
  (case type
    :double (tunable-double skill-id field-id)
    :long (tunable-int skill-id field-id)
    :string-list (tunable-string-list skill-id field-id)
    [:tuple :double 2] (tunable-double-list skill-id field-id)
    (throw (ex-info "unsupported EDN parameter type"
                    {:ability-id skill-id
                     :parameter parameter-id
                     :field-id field-id
                     :type type}))))

(defn overlay-edn-parameters
  "Materialize config values into an ability before combat-core compilation.

  EDN remains a pure declarative program: it has no config paths or config
  readers.  A missing binding is rejected so a migrated ability cannot compile
  with a silently invented parameter value.

  A schema v2 ability with no :parameters key at all (using :tunables
  instead, see overlay-edn-tunables) passes through unchanged: :parameters
  is a schema-version-1 mechanism, not something every ability must have."
  [{:keys [id parameters] :as ability}]
  (if (nil? parameters)
    ability
    (let [bindings (get edn-parameter-bindings id)]
    (when-not (map? parameters)
      (throw (ex-info "ability parameters must be a map" {:ability-id id})))
    (when-not (map? bindings)
      (throw (ex-info "missing EDN parameter bindings"
                      {:ability-id id})))
    (when-not (= (set (keys parameters)) (set (keys bindings)))
      (throw (ex-info "EDN parameter binding mismatch"
                      {:ability-id id
                       :declared (set (keys parameters))
                       :bound (set (keys bindings))})))
    (assoc ability :parameters
           (reduce-kv
             (fn [result parameter-id declaration]
               (let [field-id (get bindings parameter-id)
                     type (:type declaration)]
                 (assoc result parameter-id
                        (assoc declaration
                               :value (read-edn-parameter id parameter-id field-id type)))))
             {}
             parameters)))))

(def edn-tunable-bindings
  "Field-id overrides for schema v2 :tunables (design B) whose name doesn't
  match its config field-id. Convention over configuration: a tunable named
  :foo reads config field :foo by default, so this only needs an entry when
  that doesn't hold -- in practice, every tunable here, because field-ids
  are dotted/sectioned (:combat.damage, :targeting.range, ...) while a
  readable EDN tunable name generally isn't. Compare to
  edn-parameter-bindings above: this is still a single table entry per
  ability, not five separate places to keep in sync."
  {:railgun
   {:beam-damage :beam.damage
    :beam-radius :beam.radius
    :beam-query-radius :beam.query-radius
    :beam-step :beam.step
    :beam-block-energy :beam.block-energy
    :beam-visual-distance :beam.visual-distance
    :max-distance :beam.max-distance
    :charge-ticks :charge.item-charge-ticks
    :cost-down-cp :cost.down.cp
    :cost-down-overload :cost.down.overload}
   :arc-gen
   {:damage :combat.damage
    :max-distance :targeting.range
    :ignite-probability :effect.ignite-probability
    :fishing-probability :effect.fishing-probability
    :fishing-exp-threshold :effect.fishing-exp-threshold
    :creeper-charge-chance :effect.creeper-charge-chance
    :cost-cp :cost.down.cp
    :cost-overload :cost.down.overload
    :cooldown-ticks :cooldown.ticks
    :exp-entity :progression.exp-entity
    :exp-block :progression.exp-block}
   :flesh-ripping
   {:targeting-range :targeting.range
    :damage :combat.damage
    :nausea-chance :effect.nausea-chance
    :nausea-duration-ticks :effect.nausea-duration-ticks
    :nausea-amplifier :effect.nausea-amplifier
    :release-cp :cost.up.cp
    :release-overload :cost.up.overload
    :cooldown-ticks :cooldown.ticks
    :exp-hit :progression.exp-hit}
   :thunder-clap
   {:targeting-range :targeting.range
    :charge-min :charge.min-ticks
    :charge-max :charge.max-ticks
    :cost-down-overload :cost.down.overload
    :cost-tick-cp :cost.tick.cp
    :damage :combat.damage
    :overcharge-multiplier :combat.overcharge-multiplier
    :aoe-radius :combat.aoe-radius
    :cooldown-per-hold :cooldown.ticks-per-hold
    :exp-use :progression.exp-use}
   :shift-teleport
   {:maximum-range :targeting.range
    :damage :combat.damage
    :release-cp :cost.up.cp
    :release-overload :cost.up.overload
    :cooldown-ticks :cooldown.ticks
    :exp-base :progression.exp-base}
   :location-teleport
   {:cross-dimension-exp-threshold :targeting.cross-dimension-exp-threshold
    :teleport-radius :targeting.teleport-radius
    :cp-base :cost.perform.cp-base
    :overload :cost.perform.overload
    :cross-dimension-multiplier :cost.perform.cross-dimension-multiplier
    :min-distance-multiplier :cost.perform.min-distance-multiplier
    :distance-cap :cost.perform.distance-cap
    :cooldown-ticks :cooldown.ticks
    :long-distance-threshold :progression.long-distance-threshold
    :exp-short :progression.exp-short
    :exp-long :progression.exp-long}
   :dim-folding-theorem
   {:damage-multipliers :critical.damage-multipliers
    :level0-probability :critical.level0-probability
    :exp-per-crit-level :progression.exp-per-crit-level}
   :space-fluct
   {:damage-multipliers :critical.damage-multipliers
    :level0-probability :critical.level0-probability
    :level1-probability :critical.level1-probability
    :level2-probability :critical.level2-probability
    :exp-critical :progression.exp-critical}
   :vec-reflection
   {:tick-cp :cost.tick.cp
    :overload-keep :cost.overload-keep
    :target-radius :targeting.radius
    :affected-entity-difficulty :targeting.affected-entity-difficulty
    :excluded-entity-ids :targeting.excluded-entity-ids
    :large-fireball-ids :targeting.large-fireball-ids
    :reflect-entity-cp :cost.reflect-entity.cp
    :exp-reflect-entity-scale :progression.exp-reflect-entity-scale}
   :vec-deviation
   {:tick-cp :cost.tick.cp
    :normal-tick-cp :cost.tick.normal-cp
    :normal-tick-overload :cost.tick.normal-overload
    :target-radius :targeting.radius
    :affected-entity-difficulty :targeting.affected-entity-difficulty
    :excluded-entity-ids :targeting.excluded-entity-ids
    :excluded-tags :targeting.excluded-tags
    :large-fireball-ids :targeting.large-fireball-ids
    :small-fireball-ids :targeting.small-fireball-ids
    :deflect-cp :cost.deflect.cp
    :activation-overload :cost.activation.overload
    :fireball-explosion-radius :combat.fireball-explosion-radius
    :damage-ignore-threshold :combat.damage-ignore-threshold
    :damage-reduction :combat.damage-reduction
    :damage-cp :cost.damage.cp
    :exp-deflect-scale :progression.exp-deflect-scale
    :exp-damage-scale :progression.exp-damage-scale}
   :vec-accel
   {:max-charge-ticks :charge.max-ticks
    :max-velocity :movement.max-velocity
    :speed-progress :movement.speed-progress
    :pitch-offset-radians :movement.pitch-offset-radians
    :ground-check-distance :targeting.ground-check-distance
    :groundless-exp-threshold :targeting.groundless-exp-threshold
    :release-cp :cost.up.cp
    :release-overload :cost.up.overload
    :cooldown-ticks :cooldown.ticks
    :exp-use :progression.exp-use}
   :directed-blastwave
   {:charge-min-ticks :charge.min-ticks
    :charge-max-accepted-ticks :charge.max-accepted-ticks
    :charge-max-tolerant-ticks :charge.max-tolerant-ticks
    :punch-animation-ticks :charge.punch-anim-ticks
    :targeting-distance :targeting.raycast-distance
    :aoe-radius :combat.aoe-radius
    :damage :combat.damage
    :knockback-scale :movement.knockback-scale
    :hardness-low-threshold :breaking.hardness-low-threshold
    :hardness-mid-threshold :breaking.hardness-mid-threshold
    :hardness-caps :breaking.hardness-caps
    :break-probability :breaking.break-probability
    :drop-probability :breaking.drop-probability
    :release-cp :cost.up.cp
    :release-overload :cost.up.overload
    :cooldown-ticks :cooldown.ticks
    :exp-hit :progression.exp-hit
    :exp-miss :progression.exp-miss}
   :directed-shock
   {:charge-min-ticks :charge.min-ticks
    :charge-max-accepted-ticks :charge.max-accepted-ticks
    :charge-max-tolerant-ticks :charge.max-tolerant-ticks
    :punch-animation-ticks :charge.punch-anim-ticks
    :targeting-distance :targeting.raycast-distance
    :target-eye-height :targeting.eye-height
    :hit-impulse :movement.hit-impulse
    :knockback-y-adjust :movement.knockback-y-adjust
    :knockback-scale :movement.knockback-scale
    :knockback-exp-threshold :movement.knockback-exp-threshold
    :damage :combat.damage
    :release-cp :cost.up.cp
    :release-overload :cost.up.overload
    :cooldown-ticks :cooldown.ticks
    :exp-hit :progression.exp-hit
    :exp-miss :progression.exp-miss}
   :groundshock
   {:charge-min-ticks :charge.min-ticks
    :charge-max-tolerant-ticks :charge.max-tolerant-ticks
    :initial-energy :effect.init-energy
    :max-iterations :effect.max-iterations
    :entity-search-radius :combat.entity-search-radius
    :damage :combat.damage
    :launch-base :movement.launch-random-base
    :launch-span :movement.launch-random-span
    :launch-scale :movement.launch-scale
    :ground-break-probability :breaking.ground-break-probability
    :drop-probability :breaking.drop-rate
    :energy-cost-stone :effect.energy-cost.stone
    :energy-cost-grass :effect.energy-cost.grass-block
    :energy-cost-farmland :effect.energy-cost.farmland
    :energy-cost-default :effect.energy-cost.default-block
    :mastery-exp-threshold :breaking.mastery-exp-threshold
    :mastery-radius :breaking.mastery-radius
    :mastery-hardness-cap :breaking.mastery-hardness-cap
    :release-cp :cost.up.cp
    :release-overload :cost.up.overload
    :cooldown-ticks :cooldown.ticks
    :exp-entity :progression.exp-entity
    :exp-use :progression.exp-use}
   :blood-retrograde
   {:max-charge-ticks :charge.max-ticks
    :targeting-distance :targeting.distance
    :entity-search-radius :targeting.entity-search-radius
    :release-cp :cost.release.cp
    :release-overload :cost.release.overload
    :damage :combat.damage
    :cooldown-ticks :cooldown.ticks
    :exp-hit :progression.exp-hit
    :fx-ratio-ticks :charge.fx-ratio-ticks
    :fallback-width :targeting.fallback-width
    :fallback-height :targeting.fallback-height
    :fallback-eye-height :targeting.fallback-eye-height
    :spray-angles :effect.spray-angles}
   :meltdowner
   {:charge-min-ticks :charge.min-ticks
    :charge-max-ticks :charge.max-ticks
    :charge-max-tolerant-ticks :charge.max-tolerant-ticks
    :charge-time-rate :charge.time-rate
    :beam-radius :beam.radius
    :beam-query-radius :beam.query-radius
    :beam-step :beam.step
    :beam-max-distance :beam.max-distance
    :beam-visual-distance :beam.visual-distance
    :beam-damage :combat.damage
    :beam-block-energy :beam.block-energy
    :reflection-shot-distance :reflection.shot-distance
    :reflection-damage-multiplier :reflection.damage-multiplier
    :reflection-base-damage :reflection.base-damage
    :cost-down-overload :cost.down.overload
    :cost-tick-cp :cost.tick.cp
    :cooldown-base-multiplier :cooldown.base-multiplier
    :cooldown-ticks :cooldown.ticks
    :exp-use :progression.exp-use}
   :electron-bomb
   {:damage :combat.damage
    :cooldown-ticks :cooldown.ticks
    :exp-hit :progression.exp-hit
    :settle-ticks :charge.settle-ticks
    :settle-ticks-improved :charge.settle-ticks-improved
    :improved-exp-threshold :charge.improved-exp-threshold}
   :electron-missile
   {:seek-range :targeting.seek-range
    :max-hold-ticks :charge.max-hold-ticks
    :max-balls :projectile.max-hold-balls
    :spawn-interval-ticks :timing.spawn-interval-ticks
    :fire-interval-ticks :timing.fire-interval-ticks
    :damage :combat.damage
    :cost-down-overload :cost.down.overload
    :cost-attack-cp :cost.attack.cp
    :cost-attack-overload :cost.attack.overload
    :cost-tick-cp :cost.tick.cp
    :cooldown-ticks :cooldown.ticks
    :exp-hit :progression.exp-hit}
   :rad-intensify
   {:damage-rate :combat.damage-rate
    :mark-duration-ticks :effect.mark-duration-ticks
    :mastery-denominator :progression.mastery-denominator}
   :mine-ray-basic
   {:targeting-range :targeting.range
    :break-speed :mining.break-speed
    :cost-down-overload :cost.down.overload
    :cost-tick-cp :cost.tick.cp
    :cooldown-ticks :cooldown.ticks
    :exp-block :progression.exp-block}
   :mine-ray-expert
   {:targeting-range :targeting.range
    :break-speed :mining.break-speed
    :cost-down-overload :cost.down.overload
    :cost-tick-cp :cost.tick.cp
    :cooldown-ticks :cooldown.ticks
    :exp-block :progression.exp-block}
   :mine-ray-luck
   {:targeting-range :targeting.range
    :break-speed :mining.break-speed
    :cost-down-overload :cost.down.overload
    :cost-tick-cp :cost.tick.cp
    :cooldown-ticks :cooldown.ticks
    :exp-block :progression.exp-block}
   :scatter-bomb
   {:max-balls :projectile.max-balls
    :max-hold-ticks :projectile.max-hold-ticks
    :spawn-interval-ticks :projectile.spawn-interval-ticks
    :spawn-start-tick :projectile.spawn-start-tick
    :scatter-range :projectile.scatter-range
    :scatter-angle-degrees :projectile.scatter-angle-degrees
    :damage :combat.damage
    :auto-aim-exp-threshold :targeting.auto-aim-exp-threshold
    :auto-aim-radius :targeting.auto-aim-radius
    :anti-afk-tick :effect.anti-afk-tick
    :anti-afk-damage :effect.anti-afk-damage
    :cost-down-overload :cost.down.overload
    :cost-tick-cp :cost.tick.cp
    :exp-per-ball :progression.exp-per-ball}
   :mark-teleport
   {:minimum-distance :targeting.min-distance
    :maximum-range :targeting.range
    :range-per-hold-tick :targeting.range-per-hold-tick
    :cp-per-block :cost.up.cp-per-block
    :release-overload :cost.up.overload
    :cooldown-ticks :cooldown.ticks
    :exp-per-distance :progression.exp-per-distance
    :entity-eye-height :targeting.eye-height}
   :penetrate-teleport
   {:max-distance :targeting.max-distance
    :cp-per-block :cost.up.cp-per-block
    :release-overload :cost.up.overload
    :cooldown-ticks :cooldown.ticks
    :exp-per-distance :progression.exp-per-distance
    :scan-step :targeting.scan-step}
   :threatening-teleport
   {:maximum-range :targeting.range
    :damage :combat.damage
    :needle-damage-multiplier :combat.needle-damage-multiplier
    :release-cp :cost.up.cp
    :release-overload :cost.up.overload
    :cooldown-ticks :cooldown.ticks
    :exp-base :progression.exp-base
    :exp-hit-factor :progression.exp-hit-factor
    :exp-miss-factor :progression.exp-miss-factor
    :drop-prob-hit :interaction.drop-prob.hit
    :drop-prob-miss :interaction.drop-prob.miss}
   :flashing
   {:blink-distance :movement.blink-distance
    :blink-interval-ticks :timing.blink-interval-ticks
    :max-active-ticks :timing.max-active-ticks
    :post-blink-fall-protect-ticks :timing.post-blink-fall-protect-ticks
    :activate-overload :cost.down.overload
    :activate-cp :cost.down.cp
    :blink-cp :cost.blink.cp
    :blink-overload :cost.blink.overload
    :deactivate-cooldown-ticks :cooldown.deactivate-ticks
    :exp-per-blink :progression.exp-blink}
   :plasma-cannon
   {:charge-time :charge.time
    :cost-tick-cp :cost.tick.cp
    :overload-keep :cost.overload-keep
    :targeting-distance :targeting.raycast-distance
    :block-hit-extra-distance :projectile.block-hit-extra-distance
    :max-flight-ticks :projectile.max-flight-ticks
    :damage :combat.damage
    :damage-radius :combat.damage-radius
    :explosion-radius :combat.explosion-radius
    :cooldown-ticks :cooldown.ticks
    :exp-use :progression.exp-use
    :eye-height :targeting.eye-height
    :spawn-y-offset :projectile.spawn-y-offset
    :destination-epsilon :projectile.destination-epsilon
    :sync-interval-ticks :projectile.sync-interval-ticks
    :ground-search-distance :effect.tornado-ground-search-distance}
   :storm-wing
   {:charge-time :charge.time
    :movement-acceleration :movement.acceleration
    :movement-speed-exp-threshold :movement.speed-exp-threshold
    :movement-speed-multipliers :movement.speed-multipliers
    :movement-speed-scale :movement.speed-scale
    :hover-near-ground-velocity :movement.hover-near-ground-velocity
    :hover-air-velocity :movement.hover-air-velocity
    :near-ground-distance :targeting.near-ground-distance
    :near-ground-eye-height :targeting.near-ground-eye-height
    :low-exp-threshold :breaking.low-exp-threshold
    :soft-block-tries :breaking.soft-block-tries
    :soft-block-search-radius :breaking.soft-block-search-radius
    :soft-hardness-max :breaking.soft-hardness-max
    :mastery-knockback-radius :combat.mastery-knockback-radius
    :mastery-knockback-speed :combat.mastery-knockback-speed
    :cost-tick-cp :cost.tick.cp
    :cost-tick-overload :cost.tick.overload
    :cooldown-ticks :cooldown.ticks
    :exp-tick :progression.exp-tick}
   :ray-barrage
   {:plain-damage :combat.damage.plain
    :scattered-damage :combat.damage.scattered
    :targeting-range :targeting.range
    :scatter-cone-angle :scatter.cone-angle-degrees
    :cost-down-cp :cost.down.cp
    :cost-down-overload :cost.down.overload
    :cooldown-ticks :cooldown.ticks
    :exp-hit :progression.exp-hit}
   :current-charging
   {:visual-max-ticks :charge.visual-max-ticks
    :targeting-range :targeting.range
    :charge-amount :effect.charge-amount
    :cost-down-overload :cost.down.overload
    :cost-tick-cp :cost.tick.cp
    :exp-effective :progression.exp-effective
    :exp-ineffective :progression.exp-ineffective}
    :thunder-bolt
   {:targeting-range :targeting.range
    :direct-damage :combat.direct-damage
    :aoe-radius :combat.aoe-radius
    :aoe-damage :combat.aoe-damage
    :slowness-chance :effect.slowness-chance
    :slowness-exp-threshold :effect.slowness-exp-threshold
    :slowness-duration-ticks :effect.slowness-duration-ticks
    :slowness-aoe-retry-duration-ticks :effect.slowness-aoe-retry-duration-ticks
    :slowness-amplifier :effect.slowness-amplifier
    :creeper-charge-chance :effect.creeper-charge-chance
    :cost-down-cp :cost.down.cp
    :cost-down-overload :cost.down.overload
    :cooldown-ticks :cooldown.ticks
    :exp-effective :progression.exp-effective
     :exp-ineffective :progression.exp-ineffective}
    :jet-engine
    {:target-range :targeting.range
     :damage :combat.damage
     :hold-required-cp :cost.hold.required-cp
     :release-cp :cost.release.cp
     :release-overload :cost.release.overload
     :cooldown-ticks :cooldown.ticks
     :progression-exp-use :progression.exp-use}
    :light-shield
    {:touch-damage :combat.touch-damage
     :touch-radius :combat.touch-radius
     :absorb-damage :combat.absorb-damage
     :absorb-interval-ticks :combat.absorb-interval-ticks
     :front-cone-degrees :combat.front-cone-degrees
     :max-active-ticks :timing.max-active-ticks
     :slowness-duration-ticks :effect.slowness-duration-ticks
     :slowness-amplifier :effect.slowness-amplifier
     :activate-overload :cost.down.overload
     :tick-cp :cost.tick.cp
     :touch-cp :cost.absorb.cp
     :touch-overload :cost.absorb.overload
     :absorb-cp :cost.absorb.cp
     :absorb-overload :cost.absorb.overload
     :exp-tick :progression.exp-tick
     :exp-touch :progression.exp-touch
     :exp-attacked :progression.exp-attacked}
    :body-intensify
   {:charge-min-ticks :charge.min-ticks
    :charge-max-ticks :charge.max-ticks
    :charge-max-tolerant-ticks :charge.max-tolerant-ticks
    :effect-probability-offset-ticks :effect.probability-offset-ticks
    :effect-probability-divisor :effect.probability-divisor
    :effect-duration-multiplier :effect.duration-multiplier
    :effect-hunger-multiplier :effect.hunger-multiplier
    :effect-hunger-amplifier :effect.hunger-amplifier
    :effect-available-effects :effect.available-effects
    :cost-down-overload :cost.down.overload
    :cost-tick-cp :cost.tick.cp
    :cooldown-ticks :cooldown.ticks
    :progression-exp-use :progression.exp-use}
   :mine-detect
   {:targeting-range :targeting.range
    :blindness-duration-ticks :effect.blindness-duration-ticks
    :blindness-amplifier :effect.blindness-amplifier
    :cost-down-cp :cost.down.cp
    :cost-down-overload :cost.down.overload
    :cooldown-ticks :cooldown.ticks
    :exp-cast :progression.exp-cast}
   :mag-movement
   {:targeting-range :targeting.range
    :acceleration :movement.acceleration
    :weak-metal-exp-threshold :targeting.weak-metal-exp-threshold
    :cost-down-overload :cost.down.overload
    :cost-tick-cp :cost.tick.cp
    :exp-min :progression.exp-min
    :exp-distance-scale :progression.exp-distance-scale}
   :mag-manip
   {:targeting-grab-range :targeting.grab-range
    :targeting-throw-range :targeting.throw-range
    :targeting-max-hold-distance :targeting.max-hold-distance
    :movement-hold-distance :movement.hold-distance
    :movement-hold-head-y-offset :movement.hold-head-y-offset
    :movement-throw-speed :movement.throw-speed
    :cost-up-cp :cost.up.cp
    :cost-up-overload :cost.up.overload
    :cooldown-ticks :cooldown.ticks
    :progression-exp-throw :progression.exp-throw
    :throw-damage :combat.throw-damage}})

(defn- read-tunable-materialization
  "The config-driven piece of a :tunables declaration -- a constant value for
  :const, or the raw (lo,hi)/(base,slope) config pair for :mastery-lerp and
  :affine. The curve itself (lerp against live skill-exp, or base+slope*exp)
  is applied fresh per activation by combat_runtime's caster-facade sibling,
  never baked in here: catalog load only ever sees config, never a player's
  mastery."
  [skill-id tunable-id field-id {:keys [curve type source-skill-id]}]
  (let [config-skill-id (or source-skill-id skill-id)]
  (case curve
    :const (case (or type :double)
             :double {:value (tunable-double config-skill-id field-id)}
             :long {:value (tunable-int config-skill-id field-id)}
             :string-list {:value (tunable-string-list config-skill-id field-id)}
             (throw (ex-info "unsupported :const tunable type"
                             {:ability-id skill-id :tunable tunable-id :type type})))
    :mastery-lerp {:range (tunable-double-list config-skill-id field-id)}
    :affine {:range (tunable-double-list config-skill-id field-id)}
    ;; A raw (lo,hi) config pair with NO automatic curve applied against
    ;; skill-exp -- for a tunable that needs to be interpolated against
    ;; something other than mastery (e.g. a runtime charge ratio). The
    ;; ability reads both ends via {:tunable name :path [0]}/[1] and
    ;; supplies its own math/lerp.
    :pair {:value (tunable-double-list config-skill-id field-id)}
    (throw (ex-info "unsupported tunable curve"
                    {:ability-id skill-id :tunable tunable-id :curve curve}))))
  )

(defn overlay-edn-tunables
  "Materialize config-driven values into an ability's :tunables block before
  combat-core compilation (schema v2 design B).

  Additive and safe for content that doesn't declare :tunables: no
  schema-version-1 ability (the only abilities shipped so far) has a
  :tunables key, so this is a no-op for all of them today."
  [{:keys [id tunables] :as ability}]
  (if-not (map? tunables)
    ability
    (let [overrides (get edn-tunable-bindings id {})]
      (assoc ability :tunables
             (reduce-kv
               (fn [result tunable-id declaration]
                 (let [field-id (get overrides tunable-id tunable-id)]
                   (assoc result tunable-id
                          (merge declaration
                                 (read-tunable-materialization
                                  id tunable-id field-id declaration)))))
               {}
               tunables)))))

(defn- skill-field-default
  [skill-def {:keys [id spec-key default]}]
  (get skill-def (or spec-key id) default))

(defn- descriptor-for
  [skill-def {:keys [id path section-suffix type min max list-count comment] :as field-def}]
  (cond-> {:key (config-key (:id skill-def) id)
           :path (str (name (:id skill-def)) "." path)
           :section (keyword (str (name (:id skill-def)) "." section-suffix))
           :type type
           :default (skill-field-default skill-def field-def)
           :comment comment}
    (some? min) (assoc :min min)
    (some? max) (assoc :max max)
    (some? list-count) (assoc :list-count list-count)))

(defn- field-definitions-for-skill
  [skill-id]
  (concat field-definitions
          (get skill-tunable-definitions-by-skill skill-id [])))

(defn- descriptors-for-category
  [category-id]
  ;; mapcat + map instead of 2-binding `for` (shorter AOT class names).
  (into []
        (mapcat (fn [skill-def]
                  (map (fn [field-def]
                         (descriptor-for skill-def field-def))
                       (field-definitions-for-skill (:id skill-def))))
                (get skills-by-category category-id))))

(def descriptors-by-category
  (into {} (map (fn [category-id]
                  [category-id (descriptors-for-category category-id)])
                category-ids)))

(def descriptors-by-domain
  (into {} (map (fn [category-id]
                  [(config-common/ability-skill-category-domain category-id)
                   (get descriptors-by-category category-id)])
                category-ids)))

(def default-values-by-category
  (into {} (map (fn [category-id]
                  [category-id
                   (into {}
                         (map #(vector (get % :key) (get % :default))
                              (get descriptors-by-category category-id)))])
                category-ids)))

(def default-values-by-domain
  (into {} (map (fn [category-id]
                  [(config-common/ability-skill-category-domain category-id)
                   (get default-values-by-category category-id)])
                category-ids)))

(defn skill-configured?
  [skill-id]
  (contains? skill-definitions-by-id skill-id))

(defn category-domain
  [category-id]
  (config-common/ability-skill-category-domain category-id))

(defn- skill-domain
  [skill-id]
  (some-> (get-in skill-definitions-by-id [skill-id :category-id]) category-domain))

(defn- public-field-definition
  [skill-id field-id]
  (or (get field-definitions-by-id field-id)
      (get-in skill-tunable-definitions-by-skill-field [skill-id field-id])))

(defn- internal-field-definition
  [skill-id field-id]
  (get-in internal-tunable-definitions-by-skill-field [skill-id field-id]))

(defn- field-definition
  [skill-id field-id]
  (or (public-field-definition skill-id field-id)
      (internal-field-definition skill-id field-id)))

(defn- field-default
  [skill-id field-id]
  (let [skill-def (get skill-definitions-by-id skill-id)
        field-def (field-definition skill-id field-id)]
    (skill-field-default skill-def field-def)))

(defn raw-value
  [skill-id field-id]
  (let [domain (skill-domain skill-id)
        k (config-key skill-id field-id)
        fallback (field-default skill-id field-id)]
    (if (and domain (public-field-definition skill-id field-id))
      (get (config-reg/get-config-values domain) k fallback)
      fallback)))

(defn- non-negative-double
  [skill-id field-id]
  (let [default (double (field-default skill-id field-id))
        ^double value (ability-config-common/finite-double (raw-value skill-id field-id) default)]
    (if (neg? value) default value)))

(defn- positive-double
  [skill-id field-id]
  (let [default (double (field-default skill-id field-id))
        ^double value (ability-config-common/finite-double (raw-value skill-id field-id) default)]
    (if (pos? value) value default)))

(defn- int-in-range
  [skill-id field-id]
  (let [{lower-bound :min upper-bound :max} (field-definition skill-id field-id)
        default (int (field-default skill-id field-id))
      rounded-input (double (ability-config-common/finite-double (raw-value skill-id field-id) default))
      value (Math/round rounded-input)]
    (cond-> value
      (some? lower-bound) (max lower-bound)
      (some? upper-bound) (min upper-bound))))

(defn- within-bounds?
  [{lower-bound :min upper-bound :max} value]
  (and (or (nil? lower-bound) (<= (double lower-bound) (double value)))
       (or (nil? upper-bound) (<= (double value) (double upper-bound)))))

(defn tunable-double
  "Read a skill-specific action tunable as a bounded double.

  Invalid runtime values fall back to the descriptor default. This keeps bad
  server config edits from leaking NaN/Infinity/negative geometry into skill
  execution."
  [skill-id field-id]
  (let [field-def (field-definition skill-id field-id)
        default (double (field-default skill-id field-id))
      value (ability-config-common/finite-double (raw-value skill-id field-id) default)]
    (if (within-bounds? field-def value)
      value
      default)))

(defn tunable-int
  "Read a skill-specific action tunable as a bounded integer.

  Unlike core level config, action tunables fall back instead of clamping so an
  accidental out-of-range edit cannot silently reshape mechanics."
  [skill-id field-id]
  (let [field-def (field-definition skill-id field-id)
        default (int (field-default skill-id field-id))
      rounded-input (double (ability-config-common/finite-double (raw-value skill-id field-id) default))
      value (Math/round rounded-input)]
    (if (within-bounds? field-def value)
      value
      default)))

(defn tunable-double-list
  "Read a fixed-length list of bounded doubles for a skill action tunable.

  Length mismatches fall back to the descriptor default. Individual invalid
  entries fall back to their corresponding default entry."
  [skill-id field-id]
  (let [{:keys [list-count] :as field-def} (field-definition skill-id field-id)
        fallback (vec (field-default skill-id field-id))
        raw (raw-value skill-id field-id)]
    (if (and (ability-config-common/list-like? raw)
             (or (nil? list-count) (= (int list-count) (count raw))))
      (mapv (fn [value default]
              (let [d (ability-config-common/finite-double value default)]
                (if (within-bounds? field-def d)
                  d
                  (double default))))
            raw
            fallback)
      fallback)))

(defn tunable-int-list
  "Read a fixed-length list of bounded integers for a skill action tunable."
  [skill-id field-id]
  (let [{:keys [list-count] :as field-def} (field-definition skill-id field-id)
        fallback (vec (field-default skill-id field-id))
        raw (raw-value skill-id field-id)]
    (if (and (ability-config-common/list-like? raw)
             (or (nil? list-count) (= (int list-count) (count raw))))
      (mapv (fn [value default]
          (let [rounded-input (double (ability-config-common/finite-double value default))
            i (Math/round rounded-input)]
                (if (within-bounds? field-def i)
                  i
                  (int default))))
            raw
            fallback)
      fallback)))

(defn tunable-string-list
  "Read a list of strings for a skill action tunable.

  Blank entries are ignored. Non-list runtime edits fall back to defaults."
  [skill-id field-id]
  (let [fallback (vec (field-default skill-id field-id))
        raw (raw-value skill-id field-id)]
    (if (ability-config-common/list-like? raw)
      (let [values (->> raw
                        (map str)
                        (map str/trim)
                        (remove str/blank?)
                        vec)]
        (if (seq values) values fallback))
      fallback)))

(defn lerp-double
  [skill-id field-id exp]
  (let [[from to] (tunable-double-list skill-id field-id)]
    (+ (double from) (* (- (double to) (double from)) (double exp)))))

(defn lerp-int
  [skill-id field-id exp]
  (let [value (double (lerp-double skill-id field-id exp))]
    (int (Math/round value))))

(defn probability
  [skill-id field-id]
  (max 0.0 (min 1.0 (tunable-double skill-id field-id))))

(defn- boolean-value
  [skill-id field-id]
  (ability-config-common/boolean-value
    (raw-value skill-id field-id)
    (field-default skill-id field-id)))

(defn tunable-boolean
  [skill-id field-id]
  (boolean-value skill-id field-id))

(defn skill-enabled?
  [skill-id]
  (boolean-value skill-id :enabled))

(defn skill-controllable?
  [skill-id]
  (boolean-value skill-id :controllable))

(defn skill-level
  [skill-id]
  (int-in-range skill-id :level))

(defn destroy-blocks-enabled?
  [skill-id]
  (boolean-value skill-id :destroy-blocks))

(defn damage-scale
  [skill-id]
  (non-negative-double skill-id :damage-scale))

(defn cp-consume-speed
  [skill-id]
  (non-negative-double skill-id :cp-consume-speed))

(defn overload-consume-speed
  [skill-id]
  (non-negative-double skill-id :overload-consume-speed))

(defn exp-incr-speed
  [skill-id]
  (positive-double skill-id :exp-incr-speed))

(defn cooldown-scale
  [skill-id]
  (non-negative-double skill-id :cooldown-scale))

(defn cost-cp-scale
  [skill-id]
  (non-negative-double skill-id :cost-cp-scale))

(defn cost-overload-scale
  [skill-id]
  (non-negative-double skill-id :cost-overload-scale))

(defn- scale-value
  [value scale]
  (cond
    (fn? value)
    ;; Cost/cooldown fns are called either with a ctx map (current convention)
    ;; or legacy positional (player-id skill-id exp) — the scaled wrapper must
    ;; accept both, delegating to whichever arity `value` implements.
    (fn scaled
      ([ctx]
       (* (double scale)
          (double (or (try
                        (value ctx)
                        (catch clojure.lang.ArityException _
                          (value (:player-id ctx) (:skill-id ctx) (double (or (:exp ctx) 0.0)))))
                      0.0))))
      ([player-id skill-id exp]
       (* (double scale)
          (double (or (try
                        (value player-id skill-id exp)
                        (catch clojure.lang.ArityException _
                          (value {:player-id player-id :skill-id skill-id :exp exp})))
                      0.0)))))

    (number? value)
    (* (double scale) (double value))

    :else
    value))

(defn- scale-cost-stage
  [cost-stage cp-scale overload-scale]
  (cond-> cost-stage
    (contains? cost-stage :cp) (update :cp scale-value cp-scale)
    (contains? cost-stage :overload) (update :overload scale-value overload-scale)))

(defn- scale-cost
  [cost cp-scale overload-scale]
  (if (map? cost)
    (into {}
          (map (fn [[stage cost-stage]]
                 [stage (if (map? cost-stage)
                          (scale-cost-stage cost-stage cp-scale overload-scale)
                          cost-stage)]))
          cost)
    cost))

(defn- scale-cooldown-policy
  [policy cooldown-scale]
  (if (and (map? policy) (contains? policy :ticks))
    (update policy :ticks scale-value cooldown-scale)
    policy))

(defn apply-skill-overrides
  "Return a skill spec with the current per-skill config overlaid.

  The base registry keeps immutable skill definitions. This function is called
  when specs are read, so Forge config reloads are visible without re-registering
  content namespaces."
  [{:keys [id] :as spec}]
  (if-not (skill-configured? id)
    spec
    (let [cp-scale (cost-cp-scale id)
          overload-scale (cost-overload-scale id)
          cd-scale (cooldown-scale id)]
      (cond-> spec
        true (assoc :enabled (skill-enabled? id)
                    :controllable? (skill-controllable? id)
                    :level (skill-level id)
                    :destroy-blocks? (destroy-blocks-enabled? id)
                    :damage-scale (damage-scale id)
                    :cp-consume-speed (cp-consume-speed id)
                    :overload-consume-speed (overload-consume-speed id)
                    :exp-incr-speed (exp-incr-speed id))
        (contains? spec :cost) (update :cost scale-cost cp-scale overload-scale)
        (contains? spec :cooldown-ticks) (update :cooldown-ticks scale-value cd-scale)
        (contains? spec :cooldown-policy) (update :cooldown-policy scale-cooldown-policy cd-scale)))))

(defn- collect-config-errors
  []
  (vec
    (mapcat (fn [skill-id]
              (let [level-error
                    (when-not (<= 1 (skill-level skill-id) 5)
                      [(str (name skill-id) ".general.level must be between 1 and 5")])
                    non-negative-errors
                    (for [field-id [:damage-scale :cp-consume-speed :overload-consume-speed
                                    :cooldown-scale :cost-cp-scale :cost-overload-scale]
                          :let [value (ability-config-common/finite-double (raw-value skill-id field-id) -1.0)]
                          :when (neg? value)]
                      (str (name skill-id) "." (name field-id) " must be non-negative"))
                    exp-speed (ability-config-common/finite-double (raw-value skill-id :exp-incr-speed) 0.0)
                    exp-error (when-not (pos? exp-speed)
                                [(str (name skill-id) ".progression.exp-incr-speed must be positive")])]
                (concat level-error non-negative-errors exp-error)))
            all-skill-ids)))

(defn validate-config!
  []
  (let [errors (collect-config-errors)]
    (when (seq errors)
      (throw (ex-info "Invalid ability skill configuration" {:errors errors})))
    nil))
