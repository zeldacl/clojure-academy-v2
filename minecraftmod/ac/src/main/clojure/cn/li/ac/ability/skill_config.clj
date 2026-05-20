(ns cn.li.ac.ability.skill-config
  "Per-skill ability configuration descriptors and effective-spec helpers.

  This namespace is the SSoT for player-facing skill balance config. It stays
  platform-neutral: AC owns descriptors/defaults/getters, while Forge/Fabric
  expose the domains as TOML/JSON through the generic mcmod config bridge."
  (:require [clojure.string :as str]
            [cn.li.ac.config.common :as config-common]
            [cn.li.mcmod.config.registry :as config-reg]))

(def category-ids
  [:electromaster :meltdowner :teleporter :vecmanip])

(def skill-definitions
  [{:id :arc-gen :category-id :electromaster :level 1 :controllable? true}
   {:id :body-intensify :category-id :electromaster :level 4 :controllable? true}
   {:id :current-charging :category-id :electromaster :level 2 :controllable? true}
   {:id :mag-manip :category-id :electromaster :level 3 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :mag-movement :category-id :electromaster :level 3 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :mine-detect :category-id :electromaster :level 2 :controllable? false}
   {:id :railgun :category-id :electromaster :level 3 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :thunder-bolt :category-id :electromaster :level 2 :controllable? false}
   {:id :thunder-clap :category-id :electromaster :level 1 :controllable? true}

   {:id :electron-bomb :category-id :meltdowner :level 1 :controllable? false}
   {:id :electron-missile :category-id :meltdowner :level 5 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :jet-engine :category-id :meltdowner :level 4 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :light-shield :category-id :meltdowner :level 2 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :meltdowner :category-id :meltdowner :level 3 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :mine-ray-basic :category-id :meltdowner :level 3 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :mine-ray-expert :category-id :meltdowner :level 4 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :mine-ray-luck :category-id :meltdowner :level 5 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :rad-intensify :category-id :meltdowner :level 1 :controllable? false}
   {:id :ray-barrage :category-id :meltdowner :level 4 :controllable? false}
   {:id :scatter-bomb :category-id :meltdowner :level 2 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}

   {:id :dim-folding-theorem :category-id :teleporter :level 1 :controllable? false}
   {:id :flashing :category-id :teleporter :level 5 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :flesh-ripping :category-id :teleporter :level 3 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :location-teleport :category-id :teleporter :level 3 :controllable? false :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :mark-teleport :category-id :teleporter :level 2 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :penetrate-teleport :category-id :teleporter :level 2 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :shift-teleport :category-id :teleporter :level 4 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :space-fluct :category-id :teleporter :level 4 :controllable? false}
   {:id :threatening-teleport :category-id :teleporter :level 1 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}

   {:id :blood-retrograde :category-id :vecmanip :level 4 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :directed-blastwave :category-id :vecmanip :level 3 :controllable? false}
   {:id :directed-shock :category-id :vecmanip :level 1 :controllable? false}
   {:id :groundshock :category-id :vecmanip :level 1 :controllable? false :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :plasma-cannon :category-id :vecmanip :level 5 :controllable? false :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :storm-wing :category-id :vecmanip :level 3 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :vec-accel :category-id :vecmanip :level 2 :controllable? false}
   {:id :vec-deviation :category-id :vecmanip :level 2 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}
   {:id :vec-reflection :category-id :vecmanip :level 4 :controllable? true :cp-consume-speed 0.0 :overload-consume-speed 0.0}])

(def all-skill-ids
  (mapv :id skill-definitions))

(def skill-definitions-by-id
  (into {} (map (juxt :id identity) skill-definitions)))

(def skills-by-category
  (into {}
        (map (fn [category-id]
               [category-id (vec (filter #(= category-id (:category-id %)) skill-definitions))])
             category-ids)))

(def field-definitions
  [{:id :enabled
    :path "general.enabled"
    :section-suffix "general"
    :type :boolean
    :default true
    :comment "Whether this skill is enabled."}
   {:id :controllable
    :path "general.controllable"
    :section-suffix "general"
    :type :boolean
    :default true
    :spec-key :controllable?
    :comment "Whether this skill may be bound/controlled directly."}
   {:id :level
    :path "general.level"
    :section-suffix "general"
    :type :int
    :min 1
    :max 5
    :default 1
    :comment "Ability level required by this skill."}
   {:id :destroy-blocks
    :path "general.destroy-blocks"
    :section-suffix "general"
    :type :boolean
    :default true
    :spec-key :destroy-blocks?
    :comment "Whether this skill may destroy blocks when its implementation checks the flag."}
   {:id :damage-scale
    :path "combat.damage-scale"
    :section-suffix "combat"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Per-skill damage multiplier, applied on top of the global ability damage multiplier."}
   {:id :cp-consume-speed
    :path "consume.cp-speed"
    :section-suffix "consume"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Per-skill CP consumption multiplier used by runtime cost helpers."}
   {:id :overload-consume-speed
    :path "consume.overload-speed"
    :section-suffix "consume"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Per-skill overload consumption multiplier used by runtime cost helpers."}
   {:id :exp-incr-speed
    :path "progression.exp-incr-speed"
    :section-suffix "progression"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Per-skill experience gain multiplier."}
   {:id :cooldown-scale
    :path "cooldown.scale"
    :section-suffix "cooldown"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Multiplier applied to this skill's existing cooldown calculation."}
   {:id :cost-cp-scale
    :path "cost.cp-scale"
    :section-suffix "cost"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Multiplier applied to this skill's existing CP cost calculation."}
   {:id :cost-overload-scale
    :path "cost.overload-scale"
    :section-suffix "cost"
    :type :double
    :min 0.0
    :default 1.0
    :comment "Multiplier applied to this skill's existing overload cost calculation."}])

(def skill-tunable-definitions
  [{:skill-id :railgun
    :id :qte.coin-window-ms
    :path "qte.coin-window-ms"
    :section-suffix "qte"
    :type :int
    :min 1
    :default 1000
    :comment "Railgun coin QTE window duration in milliseconds."}
   {:skill-id :railgun
    :id :qte.coin-active-threshold
    :path "qte.coin-active-threshold"
    :section-suffix "qte"
    :type :double
    :min 0.0
    :max 1.0
    :default 0.6
    :comment "Railgun coin QTE progress threshold at which the window becomes active."}
   {:skill-id :railgun
    :id :qte.coin-perform-threshold
    :path "qte.coin-perform-threshold"
    :section-suffix "qte"
    :type :double
    :min 0.0
    :max 1.0
    :default 0.7
    :comment "Railgun coin QTE progress threshold required to fire immediately."}
   {:skill-id :railgun
    :id :charge.item-charge-ticks
    :path "charge.item-charge-ticks"
    :section-suffix "charge"
    :type :int
    :min 1
    :default 20
    :comment "Ticks required for the iron-item charge fallback path."}
   {:skill-id :railgun
    :id :beam.radius
    :path "beam.radius"
    :section-suffix "beam"
    :type :double
    :min 0.0
    :default 2.0
    :comment "Railgun beam collision radius."}
   {:skill-id :railgun
    :id :beam.query-radius
    :path "beam.query-radius"
    :section-suffix "beam"
    :type :double
    :min 0.0
    :default 30.0
    :comment "Entity query radius used by the railgun beam operation."}
   {:skill-id :railgun
    :id :beam.step
    :path "beam.step"
    :section-suffix "beam"
    :type :double
    :min 0.001
    :default 0.9
    :comment "Step size used while tracing the railgun beam."}
   {:skill-id :railgun
    :id :beam.max-distance
    :path "beam.max-distance"
    :section-suffix "beam"
    :type :double
    :min 0.0
    :default 50.0
    :comment "Maximum railgun beam hit distance."}
   {:skill-id :railgun
    :id :beam.visual-distance
    :path "beam.visual-distance"
    :section-suffix "beam"
    :type :double
    :min 0.0
    :default 45.0
    :comment "Railgun beam visual distance sent to FX."}
   {:skill-id :railgun
    :id :beam.damage
    :path "beam.damage"
    :section-suffix "beam"
    :type :double-list
    :min 0.0
    :list-count 2
    :default [60.0 110.0]
    :comment "Railgun beam damage lerp endpoints for skill exp 0.0 and 1.0."}
   {:skill-id :railgun
    :id :beam.block-energy
    :path "beam.block-energy"
    :section-suffix "beam"
    :type :double-list
    :min 0.0
    :list-count 2
    :default [900.0 2000.0]
    :comment "Railgun block-breaking energy lerp endpoints for skill exp 0.0 and 1.0."}
   {:skill-id :railgun
    :id :reflection.distance
    :path "reflection.distance"
    :section-suffix "reflection"
    :type :double
    :min 0.0
    :default 15.0
    :comment "Maximum distance of the secondary vec-reflection railgun shot."}
   {:skill-id :railgun
    :id :reflection.damage
    :path "reflection.damage"
    :section-suffix "reflection"
    :type :double
    :min 0.0
    :default 14.0
    :comment "Damage dealt by the secondary vec-reflection railgun shot."}
   {:skill-id :railgun
    :id :reflection.cp-consumption-per-damage
    :path "reflection.cp-consumption-per-damage"
    :section-suffix "reflection"
    :type :double-list
    :min 0.0
    :list-count 2
    :default [20.0 15.0]
    :comment "Vec-reflection CP consumption multiplier per incoming damage, lerped by vec-reflection exp."}
   {:skill-id :railgun
    :id :cost.down.cp
    :path "cost.down.cp"
    :section-suffix "cost.down"
    :type :double-list
    :min 0.0
    :list-count 2
    :default [200.0 450.0]
    :comment "Railgun coin-QTE down-stage CP cost lerp endpoints."}
   {:skill-id :railgun
    :id :cost.down.overload
    :path "cost.down.overload"
    :section-suffix "cost.down"
    :type :double-list
    :min 0.0
    :list-count 2
    :default [180.0 120.0]
    :comment "Railgun coin-QTE down-stage overload cost lerp endpoints."}
   {:skill-id :railgun
    :id :cost.tick.cp
    :path "cost.tick.cp"
    :section-suffix "cost.tick"
    :type :double-list
    :min 0.0
    :list-count 2
    :default [200.0 450.0]
    :comment "Railgun item-charge tick-stage CP cost lerp endpoints."}
   {:skill-id :railgun
    :id :cost.tick.overload
    :path "cost.tick.overload"
    :section-suffix "cost.tick"
    :type :double-list
    :min 0.0
    :list-count 2
    :default [180.0 120.0]
    :comment "Railgun item-charge tick-stage overload cost lerp endpoints."}
   {:skill-id :railgun
    :id :cooldown.manual-ticks
    :path "cooldown.manual-ticks"
    :section-suffix "cooldown"
    :type :double-list
    :min 1.0
    :list-count 2
    :default [300.0 160.0]
    :comment "Railgun manual cooldown lerp endpoints applied after a successful shot."}
   {:skill-id :railgun
    :id :progression.exp-hit
    :path "progression.exp-hit"
    :section-suffix "progression"
    :type :double
    :min 0.0
    :default 0.005
    :comment "Railgun skill exp gained for a normal successful hit."}
   {:skill-id :railgun
    :id :progression.exp-reflection-hit
    :path "progression.exp-reflection-hit"
    :section-suffix "progression"
    :type :double
    :min 0.0
    :default 0.01
    :comment "Railgun skill exp gained when a shot is reflected by vec-reflection."}

     {:skill-id :thunder-clap
    :id :targeting.range
    :path "targeting.range"
    :section-suffix "targeting"
    :type :double
    :min 0.0
    :default 40.0
    :comment "ThunderClap aim raycast range."}
     {:skill-id :thunder-clap
    :id :charge.min-ticks
    :path "charge.min-ticks"
    :section-suffix "charge"
    :type :int
    :min 1
    :default 40
    :comment "Minimum held ticks required for ThunderClap to perform."}
     {:skill-id :thunder-clap
    :id :charge.max-ticks
    :path "charge.max-ticks"
    :section-suffix "charge"
    :type :int
    :min 1
    :default 60
    :comment "Reference maximum charge ticks used by ThunderClap FX and overcharge scaling."}
     {:skill-id :thunder-clap
    :id :cost.down.overload
    :path "cost.down.overload"
    :section-suffix "cost.down"
    :type :double-list
    :min 0.0
    :list-count 2
    :default [390.0 252.0]
    :comment "ThunderClap down-stage overload cost lerp endpoints."}
     {:skill-id :thunder-clap
    :id :cost.tick.cp
    :path "cost.tick.cp"
    :section-suffix "cost.tick"
    :type :double-list
    :min 0.0
    :list-count 2
    :default [18.0 25.0]
    :comment "ThunderClap tick-stage CP cost lerp endpoints while the charge is within min-ticks."}
     {:skill-id :thunder-clap
    :id :combat.damage
    :path "combat.damage"
    :section-suffix "combat"
    :type :double-list
    :min 0.0
    :list-count 2
    :default [36.0 72.0]
    :comment "ThunderClap base damage lerp endpoints."}
     {:skill-id :thunder-clap
    :id :combat.overcharge-multiplier
    :path "combat.overcharge-multiplier"
    :section-suffix "combat"
    :type :double-list
    :min 0.0
    :list-count 2
    :default [1.0 1.2]
    :comment "ThunderClap damage multiplier lerp endpoints for charge ticks above min-ticks; default mirrors the original partial overcharge formula."}
     {:skill-id :thunder-clap
    :id :combat.aoe-radius
    :path "combat.aoe-radius"
    :section-suffix "combat"
    :type :double-list
    :min 0.0
    :list-count 2
    :default [15.0 30.0]
    :comment "ThunderClap AOE radius lerp endpoints."}
     {:skill-id :thunder-clap
    :id :cooldown.ticks-per-hold
    :path "cooldown.ticks-per-hold"
    :section-suffix "cooldown"
    :type :double-list
    :min 0.0
    :list-count 2
    :default [10.0 6.0]
    :comment "ThunderClap cooldown multiplier per held tick, lerped by skill exp."}
     {:skill-id :thunder-clap
    :id :progression.exp-use
    :path "progression.exp-use"
    :section-suffix "progression"
    :type :double
    :min 0.0
    :default 0.003
    :comment "ThunderClap skill exp gained for a successful use."}

     {:skill-id :arc-gen :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [5.0 9.0] :comment "Arc Gen direct damage lerp endpoints."}
     {:skill-id :arc-gen :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [6.0 15.0] :comment "Arc Gen raycast range lerp endpoints."}
     {:skill-id :arc-gen :id :effect.ignite-probability :path "effect.ignite-probability" :section-suffix "effect" :type :double-list :min 0.0 :max 1.0 :list-count 2 :default [0.0 0.6] :comment "Arc Gen block ignite probability lerp endpoints."}
     {:skill-id :arc-gen :id :effect.fishing-probability :path "effect.fishing-probability" :section-suffix "effect" :type :double :min 0.0 :max 1.0 :default 0.1 :comment "Arc Gen fishing trigger probability when exp threshold is met."}
     {:skill-id :arc-gen :id :effect.fishing-exp-threshold :path "effect.fishing-exp-threshold" :section-suffix "effect" :type :double :min 0.0 :max 1.0 :default 0.5 :comment "Arc Gen exp threshold required for fishing behavior."}
     {:skill-id :arc-gen :id :effect.stun-exp-threshold :path "effect.stun-exp-threshold" :section-suffix "effect" :type :double :min 0.0 :default 1.0 :comment "Arc Gen exp threshold required for stun behavior."}
     {:skill-id :arc-gen :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [30.0 70.0] :comment "Arc Gen down-stage CP cost lerp endpoints."}
     {:skill-id :arc-gen :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [18.0 11.0] :comment "Arc Gen down-stage overload cost lerp endpoints."}
     {:skill-id :arc-gen :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [15.0 5.0] :comment "Arc Gen cooldown lerp endpoints."}
     {:skill-id :arc-gen :id :progression.exp-entity :path "progression.exp-entity" :section-suffix "progression" :type :double-list :min 0.0 :list-count 2 :default [0.0048 0.0024] :comment "Arc Gen entity-hit exp formula [base, exp-scale]."}
     {:skill-id :arc-gen :id :progression.exp-block :path "progression.exp-block" :section-suffix "progression" :type :double-list :min 0.0 :list-count 2 :default [0.0018 0.0009] :comment "Arc Gen block-hit exp formula [base, exp-scale]."}
     {:skill-id :arc-gen :id :progression.exp-miss :path "progression.exp-miss" :section-suffix "progression" :type :double :min 0.0 :default 0.001 :comment "Arc Gen exp gained on miss."}

     {:skill-id :body-intensify :id :charge.min-ticks :path "charge.min-ticks" :section-suffix "charge" :type :int :min 1 :default 10 :comment "BodyIntensify minimum charge ticks required to perform."}
     {:skill-id :body-intensify :id :charge.max-ticks :path "charge.max-ticks" :section-suffix "charge" :type :int :min 1 :default 40 :comment "BodyIntensify maximum effective charge ticks."}
     {:skill-id :body-intensify :id :charge.max-tolerant-ticks :path "charge.max-tolerant-ticks" :section-suffix "charge" :type :int :min 1 :default 100 :comment "BodyIntensify maximum tolerated charge ticks before context termination."}
     {:skill-id :body-intensify :id :effect.probability-offset-ticks :path "effect.probability-offset-ticks" :section-suffix "effect" :type :double :min 0.0 :default 10.0 :comment "BodyIntensify charge ticks subtracted before random buff probability calculation."}
     {:skill-id :body-intensify :id :effect.probability-divisor :path "effect.probability-divisor" :section-suffix "effect" :type :double :min 0.001 :default 18.0 :comment "BodyIntensify probability divisor for random buff picks."}
     {:skill-id :body-intensify :id :effect.duration-multiplier :path "effect.duration-multiplier" :section-suffix "effect" :type :double-list :min 0.0 :list-count 2 :default [1.5 2.5] :comment "BodyIntensify buff duration multiplier lerp endpoints."}
     {:skill-id :body-intensify :id :effect.hunger-multiplier :path "effect.hunger-multiplier" :section-suffix "effect" :type :double :min 0.0 :default 1.25 :comment "BodyIntensify hunger duration multiplier."}
     {:skill-id :body-intensify :id :effect.hunger-amplifier :path "effect.hunger-amplifier" :section-suffix "effect" :type :int :min 0 :default 2 :comment "BodyIntensify hunger amplifier."}
     {:skill-id :body-intensify :id :effect.available-effects :path "effect.available-effects" :section-suffix "effect" :type :string-list :default ["speed:3" "jump-boost:1" "regeneration:1" "strength:1" "resistance:1"] :comment "BodyIntensify random buff pool entries as effect-id:max-amplifier."}
     {:skill-id :body-intensify :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [200.0 120.0] :comment "BodyIntensify down-stage overload cost lerp endpoints."}
     {:skill-id :body-intensify :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [20.0 15.0] :comment "BodyIntensify tick-stage CP cost lerp endpoints while charging."}
     {:skill-id :body-intensify :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 1.0 :list-count 2 :default [900.0 600.0] :comment "BodyIntensify cooldown lerp endpoints."}
     {:skill-id :body-intensify :id :progression.exp-use :path "progression.exp-use" :section-suffix "progression" :type :double :min 0.0 :default 0.01 :comment "BodyIntensify exp gained on successful release."}

     {:skill-id :current-charging :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double :min 0.0 :default 15.0 :comment "CurrentCharging block raycast range."}
     {:skill-id :current-charging :id :effect.charge-amount :path "effect.charge-amount" :section-suffix "effect" :type :double-list :min 0.0 :list-count 2 :default [15.0 35.0] :comment "CurrentCharging energy transfer amount lerp endpoints."}
     {:skill-id :current-charging :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [65.0 48.0] :comment "CurrentCharging down-stage overload cost lerp endpoints."}
     {:skill-id :current-charging :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [3.0 7.0] :comment "CurrentCharging tick-stage CP cost lerp endpoints."}
     {:skill-id :current-charging :id :progression.exp-effective :path "progression.exp-effective" :section-suffix "progression" :type :double :min 0.0 :default 0.0001 :comment "CurrentCharging exp gained per effective tick."}
     {:skill-id :current-charging :id :progression.exp-ineffective :path "progression.exp-ineffective" :section-suffix "progression" :type :double :min 0.0 :default 0.00003 :comment "CurrentCharging exp gained per ineffective tick."}

     {:skill-id :mag-manip :id :targeting.grab-range :path "targeting.grab-range" :section-suffix "targeting" :type :double :min 0.0 :default 10.0 :comment "MagManip maximum block grab range."}
     {:skill-id :mag-manip :id :targeting.throw-range :path "targeting.throw-range" :section-suffix "targeting" :type :double :min 0.0 :default 20.0 :comment "MagManip maximum throw range."}
     {:skill-id :mag-manip :id :targeting.throw-hit-radius :path "targeting.throw-hit-radius" :section-suffix "targeting" :type :double :min 0.0 :default 1.15 :comment "MagManip thrown block hit radius."}
    {:skill-id :mag-manip :id :targeting.max-hold-distance :path "targeting.max-hold-distance" :section-suffix "targeting" :type :double :min 0.0 :default 5.0 :comment "MagManip maximum distance between player and held focus before release fails."}
     {:skill-id :mag-manip :id :targeting.weak-metal-exp-threshold :path "targeting.weak-metal-exp-threshold" :section-suffix "targeting" :type :double :min 0.0 :max 1.0 :default 0.5 :comment "MagManip exp threshold for weak-metal keyword matches."}
     {:skill-id :mag-manip :id :targeting.strong-metal-blocks :path "targeting.strong-metal-blocks" :section-suffix "targeting" :type :string-list :default ["minecraft:iron_block" "minecraft:iron_ore" "minecraft:deepslate_iron_ore" "minecraft:gold_block" "minecraft:gold_ore" "minecraft:deepslate_gold_ore" "minecraft:copper_block" "minecraft:copper_ore" "minecraft:deepslate_copper_ore" "minecraft:netherite_block" "minecraft:ancient_debris" "minecraft:anvil" "minecraft:chipped_anvil" "minecraft:damaged_anvil" "minecraft:hopper" "minecraft:chain" "minecraft:iron_bars" "minecraft:iron_door" "minecraft:iron_trapdoor" "minecraft:rail" "minecraft:powered_rail" "minecraft:detector_rail" "minecraft:activator_rail"] :comment "MagManip strongly magnetic block ids."}
     {:skill-id :mag-manip :id :targeting.weak-metal-keywords :path "targeting.weak-metal-keywords" :section-suffix "targeting" :type :string-list :default ["iron" "gold" "copper" "rail" "anvil" "chain" "ore" "debris" "metal" "steel"] :comment "MagManip weak-metal substring hints."}
     {:skill-id :mag-manip :id :movement.hold-distance :path "movement.hold-distance" :section-suffix "movement" :type :double :min 0.0 :default 2.0 :comment "MagManip held block focus distance."}
     {:skill-id :mag-manip :id :movement.hold-head-y-offset :path "movement.hold-head-y-offset" :section-suffix "movement" :type :double :min 0.0 :default 0.1 :comment "MagManip held block head Y offset."}
     {:skill-id :mag-manip :id :combat.throw-damage :path "combat.throw-damage" :section-suffix "combat" :type :double :min 0.0 :default 10.0 :comment "MagManip thrown block damage."}
     {:skill-id :mag-manip :id :cost.up.cp :path "cost.up.cp" :section-suffix "cost.up" :type :double-list :min 0.0 :list-count 2 :default [140.0 270.0] :comment "MagManip up-stage CP cost lerp endpoints."}
     {:skill-id :mag-manip :id :cost.up.overload :path "cost.up.overload" :section-suffix "cost.up" :type :double-list :min 0.0 :list-count 2 :default [35.0 20.0] :comment "MagManip up-stage overload cost lerp endpoints."}
     {:skill-id :mag-manip :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 1.0 :list-count 2 :default [60.0 40.0] :comment "MagManip manual cooldown lerp endpoints."}
     {:skill-id :mag-manip :id :progression.exp-throw :path "progression.exp-throw" :section-suffix "progression" :type :double :min 0.0 :default 0.005 :comment "MagManip exp gained on successful throw."}

     {:skill-id :mag-movement :id :movement.acceleration :path "movement.acceleration" :section-suffix "movement" :type :double :min 0.0 :default 0.08 :comment "MagMovement smooth acceleration step."}
     {:skill-id :mag-movement :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double :min 0.0 :default 25.0 :comment "MagMovement target raycast range."}
     {:skill-id :mag-movement :id :targeting.weak-metal-exp-threshold :path "targeting.weak-metal-exp-threshold" :section-suffix "targeting" :type :double :min 0.0 :max 1.0 :default 0.6 :comment "MagMovement exp threshold for weak metal targeting."}
     {:skill-id :mag-movement :id :targeting.target-update-radius :path "targeting.target-update-radius" :section-suffix "targeting" :type :double :min 0.0 :default 4.0 :comment "MagMovement entity target refresh radius."}
     {:skill-id :mag-movement :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [60.0 30.0] :comment "MagMovement down-stage overload cost lerp endpoints."}
     {:skill-id :mag-movement :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [15.0 8.0] :comment "MagMovement tick-stage CP cost lerp endpoints."}
     {:skill-id :mag-movement :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :int :min 1 :default 60 :comment "MagMovement cooldown ticks."}
     {:skill-id :mag-movement :id :progression.exp-min :path "progression.exp-min" :section-suffix "progression" :type :double :min 0.0 :default 0.005 :comment "MagMovement minimum exp gained on completion."}
     {:skill-id :mag-movement :id :progression.exp-distance-scale :path "progression.exp-distance-scale" :section-suffix "progression" :type :double :min 0.0 :default 0.0011 :comment "MagMovement exp gained per traveled block."}

     {:skill-id :mine-detect :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [15.0 30.0] :comment "MineDetect scan range lerp endpoints."}
     {:skill-id :mine-detect :id :effect.blindness-duration-ticks :path "effect.blindness-duration-ticks" :section-suffix "effect" :type :int :min 0 :default 100 :comment "MineDetect blindness duration in ticks."}
     {:skill-id :mine-detect :id :effect.blindness-amplifier :path "effect.blindness-amplifier" :section-suffix "effect" :type :int :min 0 :default 0 :comment "MineDetect blindness amplifier."}
     {:skill-id :mine-detect :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [600.0 400.0] :comment "MineDetect down-stage CP cost lerp endpoints."}
     {:skill-id :mine-detect :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [80.0 50.0] :comment "MineDetect down-stage overload cost lerp endpoints."}
     {:skill-id :mine-detect :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 1.0 :list-count 2 :default [200.0 100.0] :comment "MineDetect cooldown lerp endpoints."}
     {:skill-id :mine-detect :id :progression.exp-cast :path "progression.exp-cast" :section-suffix "progression" :type :double :min 0.0 :default 0.003 :comment "MineDetect exp gained per cast."}

     {:skill-id :thunder-bolt :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double :min 0.0 :default 20.0 :comment "ThunderBolt target raycast range."}
     {:skill-id :thunder-bolt :id :combat.direct-damage :path "combat.direct-damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [10.0 25.0] :comment "ThunderBolt direct damage lerp endpoints."}
     {:skill-id :thunder-bolt :id :combat.aoe-radius :path "combat.aoe-radius" :section-suffix "combat" :type :double :min 0.0 :default 8.0 :comment "ThunderBolt AOE damage radius."}
     {:skill-id :thunder-bolt :id :combat.aoe-damage :path "combat.aoe-damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [6.0 15.0] :comment "ThunderBolt AOE damage lerp endpoints."}
     {:skill-id :thunder-bolt :id :effect.slowness-chance :path "effect.slowness-chance" :section-suffix "effect" :type :double :min 0.0 :max 1.0 :default 0.8 :comment "ThunderBolt slowness roll chance."}
     {:skill-id :thunder-bolt :id :effect.slowness-duration-ticks :path "effect.slowness-duration-ticks" :section-suffix "effect" :type :int :min 0 :default 40 :comment "ThunderBolt slowness duration in ticks."}
     {:skill-id :thunder-bolt :id :effect.slowness-amplifier :path "effect.slowness-amplifier" :section-suffix "effect" :type :int :min 0 :default 3 :comment "ThunderBolt slowness amplifier."}
     {:skill-id :thunder-bolt :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [280.0 420.0] :comment "ThunderBolt down-stage CP cost lerp endpoints."}
     {:skill-id :thunder-bolt :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [50.0 27.0] :comment "ThunderBolt down-stage overload cost lerp endpoints."}
     {:skill-id :thunder-bolt :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 1.0 :list-count 2 :default [120.0 50.0] :comment "ThunderBolt cooldown lerp endpoints."}
    {:skill-id :thunder-bolt :id :progression.exp-effective :path "progression.exp-effective" :section-suffix "progression" :type :double :min 0.0 :default 0.005 :comment "ThunderBolt effective exp gain."}
    {:skill-id :thunder-bolt :id :progression.exp-ineffective :path "progression.exp-ineffective" :section-suffix "progression" :type :double :min 0.0 :default 0.003 :comment "ThunderBolt ineffective exp gain."}

    {:skill-id :dim-folding-theorem :id :critical.damage-multipliers :path "critical.damage-multipliers" :section-suffix "critical" :type :double-list :min 0.0 :list-count 3 :default [1.3 1.6 2.6] :comment "Teleporter critical damage multipliers for crit levels 0, 1, and 2."}
    {:skill-id :dim-folding-theorem :id :critical.level0-probability :path "critical.level0-probability" :section-suffix "critical" :type :double-list :min 0.0 :max 1.0 :list-count 2 :default [0.1 0.2] :comment "DimFolding passive contribution to level-0 critical probability."}
    {:skill-id :dim-folding-theorem :id :progression.exp-per-crit-level :path "progression.exp-per-crit-level" :section-suffix "progression" :type :double :min 0.0 :default 0.005 :comment "DimFolding exp gained per critical level plus one."}
    {:skill-id :space-fluct :id :critical.level0-probability :path "critical.level0-probability" :section-suffix "critical" :type :double-list :min 0.0 :max 1.0 :list-count 2 :default [0.18 0.25] :comment "SpaceFluct passive contribution to level-0 critical probability."}
    {:skill-id :space-fluct :id :critical.level1-probability :path "critical.level1-probability" :section-suffix "critical" :type :double-list :min 0.0 :max 1.0 :list-count 2 :default [0.10 0.15] :comment "SpaceFluct passive contribution to level-1 critical probability."}
    {:skill-id :space-fluct :id :critical.level2-probability :path "critical.level2-probability" :section-suffix "critical" :type :double-list :min 0.0 :max 1.0 :list-count 2 :default [0.01 0.03] :comment "SpaceFluct passive contribution to level-2 critical probability."}
    {:skill-id :space-fluct :id :progression.exp-critical :path "progression.exp-critical" :section-suffix "progression" :type :double :min 0.0 :default 0.0001 :comment "SpaceFluct exp gained when a teleporter critical attack occurs."}

    {:skill-id :flashing :id :movement.blink-distance :path "movement.blink-distance" :section-suffix "movement" :type :double-list :min 0.0 :list-count 2 :default [0.5 1.5] :comment "Flashing blink distance per interval."}
    {:skill-id :flashing :id :timing.blink-interval-ticks :path "timing.blink-interval-ticks" :section-suffix "timing" :type :double-list :min 1.0 :list-count 2 :default [6.0 3.0] :comment "Flashing blink interval in ticks."}
    {:skill-id :flashing :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [20.0 10.0] :comment "Flashing activation overload cost."}
    {:skill-id :flashing :id :cost.blink.cp :path "cost.blink.cp" :section-suffix "cost.blink" :type :double-list :min 0.0 :list-count 2 :default [30.0 18.0] :comment "Flashing manual CP cost per blink."}
    {:skill-id :flashing :id :cost.blink.overload :path "cost.blink.overload" :section-suffix "cost.blink" :type :double-list :min 0.0 :list-count 2 :default [8.0 4.0] :comment "Flashing manual overload cost per blink."}
    {:skill-id :flashing :id :cooldown.deactivate-ticks :path "cooldown.deactivate-ticks" :section-suffix "cooldown" :type :int :min 0 :default 20 :comment "Flashing manual cooldown applied on deactivate/abort."}
    {:skill-id :flashing :id :progression.exp-blink :path "progression.exp-blink" :section-suffix "progression" :type :double :min 0.0 :default 0.001 :comment "Flashing exp gained per successful blink."}

    {:skill-id :flesh-ripping :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [15.0 25.0] :comment "FleshRipping entity raycast range."}
    {:skill-id :flesh-ripping :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [6.0 16.0] :comment "FleshRipping magic damage."}
    {:skill-id :flesh-ripping :id :effect.nausea-chance :path "effect.nausea-chance" :section-suffix "effect" :type :double :min 0.0 :max 1.0 :default 0.30 :comment "FleshRipping nausea application probability."}
    {:skill-id :flesh-ripping :id :effect.nausea-duration-ticks :path "effect.nausea-duration-ticks" :section-suffix "effect" :type :int :min 0 :default 60 :comment "FleshRipping nausea duration in ticks."}
    {:skill-id :flesh-ripping :id :effect.nausea-amplifier :path "effect.nausea-amplifier" :section-suffix "effect" :type :int :min 0 :default 0 :comment "FleshRipping nausea amplifier."}
    {:skill-id :flesh-ripping :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [180.0 130.0] :comment "FleshRipping down-stage CP cost."}
    {:skill-id :flesh-ripping :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [70.0 50.0] :comment "FleshRipping down-stage overload cost."}
    {:skill-id :flesh-ripping :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [30.0 18.0] :comment "FleshRipping cooldown ticks."}
    {:skill-id :flesh-ripping :id :progression.exp-hit :path "progression.exp-hit" :section-suffix "progression" :type :double :min 0.0 :default 0.003 :comment "FleshRipping exp gained per hit."}

    {:skill-id :mark-teleport :id :targeting.min-distance :path "targeting.min-distance" :section-suffix "targeting" :type :double :min 0.0 :default 3.0 :comment "MarkTeleport minimum valid teleport distance."}
    {:skill-id :mark-teleport :id :targeting.eye-height :path "targeting.eye-height" :section-suffix "targeting" :type :double :min 0.0 :default 1.6 :comment "MarkTeleport eye height used for raycast origin."}
    {:skill-id :mark-teleport :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [25.0 60.0] :comment "MarkTeleport maximum range."}
    {:skill-id :mark-teleport :id :targeting.range-per-hold-tick :path "targeting.range-per-hold-tick" :section-suffix "targeting" :type :double :min 0.0 :default 2.0 :comment "MarkTeleport range growth per held tick."}
    {:skill-id :mark-teleport :id :cost.up.cp-per-block :path "cost.up.cp-per-block" :section-suffix "cost.up" :type :double-list :min 0.0 :list-count 2 :default [12.0 4.0] :comment "MarkTeleport CP cost per block."}
    {:skill-id :mark-teleport :id :cost.up.overload :path "cost.up.overload" :section-suffix "cost.up" :type :double-list :min 0.0 :list-count 2 :default [40.0 20.0] :comment "MarkTeleport overload cost."}
    {:skill-id :mark-teleport :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [30.0 0.0] :comment "MarkTeleport manual cooldown ticks."}
    {:skill-id :mark-teleport :id :progression.exp-per-distance :path "progression.exp-per-distance" :section-suffix "progression" :type :double :min 0.0 :default 0.00018 :comment "MarkTeleport exp gained per teleported block."}

    {:skill-id :location-teleport :id :targeting.cross-dimension-exp-threshold :path "targeting.cross-dimension-exp-threshold" :section-suffix "targeting" :type :double :min 0.0 :max 1.0 :default 0.8 :comment "LocationTeleport exp threshold for cross-dimension travel."}
    {:skill-id :location-teleport :id :targeting.teleport-radius :path "targeting.teleport-radius" :section-suffix "targeting" :type :double :min 0.0 :default 5.0 :comment "LocationTeleport nearby entity teleport radius."}
    {:skill-id :location-teleport :id :ui.max-location-name-length :path "ui.max-location-name-length" :section-suffix "ui" :type :int :min 1 :default 16 :comment "LocationTeleport maximum saved location name length."}
    {:skill-id :location-teleport :id :cost.perform.cp-base :path "cost.perform.cp-base" :section-suffix "cost.perform" :type :double-list :min 0.0 :list-count 2 :default [200.0 150.0] :comment "LocationTeleport base CP cost before distance multiplier."}
    {:skill-id :location-teleport :id :cost.perform.overload :path "cost.perform.overload" :section-suffix "cost.perform" :type :double :min 0.0 :default 240.0 :comment "LocationTeleport fixed overload cost."}
    {:skill-id :location-teleport :id :cost.perform.cross-dimension-multiplier :path "cost.perform.cross-dimension-multiplier" :section-suffix "cost.perform" :type :double :min 0.0 :default 2.0 :comment "LocationTeleport CP multiplier for cross-dimension travel."}
    {:skill-id :location-teleport :id :cost.perform.min-distance-multiplier :path "cost.perform.min-distance-multiplier" :section-suffix "cost.perform" :type :double :min 0.0 :default 8.0 :comment "LocationTeleport minimum distance multiplier."}
    {:skill-id :location-teleport :id :cost.perform.distance-cap :path "cost.perform.distance-cap" :section-suffix "cost.perform" :type :double :min 0.0 :default 800.0 :comment "LocationTeleport distance cap before square root multiplier."}
    {:skill-id :location-teleport :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [30.0 20.0] :comment "LocationTeleport cooldown ticks."}
    {:skill-id :location-teleport :id :progression.long-distance-threshold :path "progression.long-distance-threshold" :section-suffix "progression" :type :double :min 0.0 :default 200.0 :comment "LocationTeleport distance threshold for long-distance exp."}
    {:skill-id :location-teleport :id :progression.exp-short :path "progression.exp-short" :section-suffix "progression" :type :double :min 0.0 :default 0.015 :comment "LocationTeleport exp for short-distance travel."}
    {:skill-id :location-teleport :id :progression.exp-long :path "progression.exp-long" :section-suffix "progression" :type :double :min 0.0 :default 0.03 :comment "LocationTeleport exp for long-distance travel."}

    {:skill-id :penetrate-teleport :id :targeting.max-depth :path "targeting.max-depth" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [3.0 6.0] :comment "PenetrateTeleport maximum penetration depth."}
    {:skill-id :penetrate-teleport :id :targeting.scan-distance :path "targeting.scan-distance" :section-suffix "targeting" :type :double :min 0.0 :default 30.0 :comment "PenetrateTeleport wall scan distance."}
    {:skill-id :penetrate-teleport :id :targeting.scan-step :path "targeting.scan-step" :section-suffix "targeting" :type :double :min 0.001 :default 0.5 :comment "PenetrateTeleport scan step in blocks."}
    {:skill-id :penetrate-teleport :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [200.0 140.0] :comment "PenetrateTeleport down-stage CP cost."}
    {:skill-id :penetrate-teleport :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [80.0 55.0] :comment "PenetrateTeleport down-stage overload cost."}
    {:skill-id :penetrate-teleport :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [40.0 25.0] :comment "PenetrateTeleport cooldown ticks."}
    {:skill-id :penetrate-teleport :id :progression.exp-success :path "progression.exp-success" :section-suffix "progression" :type :double :min 0.0 :default 0.003 :comment "PenetrateTeleport exp gained on success."}

    {:skill-id :shift-teleport :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [20.0 35.0] :comment "ShiftTeleport block target range."}
    {:skill-id :shift-teleport :id :targeting.eye-height :path "targeting.eye-height" :section-suffix "targeting" :type :double :min 0.0 :default 1.6 :comment "ShiftTeleport raycast eye height."}
    {:skill-id :shift-teleport :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [120.0 80.0] :comment "ShiftTeleport down-stage CP cost."}
    {:skill-id :shift-teleport :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [50.0 35.0] :comment "ShiftTeleport down-stage overload cost."}
    {:skill-id :shift-teleport :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [25.0 15.0] :comment "ShiftTeleport cooldown ticks."}
    {:skill-id :shift-teleport :id :progression.exp-success :path "progression.exp-success" :section-suffix "progression" :type :double :min 0.0 :default 0.002 :comment "ShiftTeleport exp gained on success."}

    {:skill-id :threatening-teleport :id :charge.min-ticks :path "charge.min-ticks" :section-suffix "charge" :type :int :min 0 :default 5 :comment "ThreateningTeleport minimum hold ticks."}
    {:skill-id :threatening-teleport :id :charge.max-ticks :path "charge.max-ticks" :section-suffix "charge" :type :int :min 1 :default 60 :comment "ThreateningTeleport charge reference ticks."}
    {:skill-id :threatening-teleport :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [20.0 40.0] :comment "ThreateningTeleport entity raycast range."}
    {:skill-id :threatening-teleport :id :movement.behind-offset :path "movement.behind-offset" :section-suffix "movement" :type :double :min 0.0 :default 1.5 :comment "ThreateningTeleport destination offset behind target."}
    {:skill-id :threatening-teleport :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [5.0 14.0] :comment "ThreateningTeleport base magic damage."}
    {:skill-id :threatening-teleport :id :combat.charge-bonus-multiplier :path "combat.charge-bonus-multiplier" :section-suffix "combat" :type :double :min 0.0 :default 1.0 :comment "ThreateningTeleport additional full-charge damage multiplier."}
    {:skill-id :threatening-teleport :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [150.0 100.0] :comment "ThreateningTeleport down-stage CP cost."}
    {:skill-id :threatening-teleport :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [60.0 40.0] :comment "ThreateningTeleport down-stage overload cost."}
    {:skill-id :threatening-teleport :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [35.0 20.0] :comment "ThreateningTeleport cooldown ticks."}
    {:skill-id :threatening-teleport :id :progression.exp-success :path "progression.exp-success" :section-suffix "progression" :type :double :min 0.0 :default 0.003 :comment "ThreateningTeleport exp gained on success."}

    {:skill-id :rad-intensify :id :combat.damage-rate :path "combat.damage-rate" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [1.4 1.8] :comment "RadIntensify damage amplification rate endpoints."}
    {:skill-id :rad-intensify :id :effect.mark-duration-ms :path "effect.mark-duration-ms" :section-suffix "effect" :type :int :min 1 :default 3000 :comment "RadIntensify target mark duration in milliseconds."}

    {:skill-id :electron-bomb :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [6.0 12.0] :comment "ElectronBomb beam damage endpoints."}
    {:skill-id :electron-bomb :id :beam.radius :path "beam.radius" :section-suffix "beam" :type :double :min 0.0 :default 0.3 :comment "ElectronBomb delayed beam radius."}
    {:skill-id :electron-bomb :id :beam.query-radius :path "beam.query-radius" :section-suffix "beam" :type :double :min 0.0 :default 20.0 :comment "ElectronBomb delayed beam entity query radius."}
    {:skill-id :electron-bomb :id :beam.step :path "beam.step" :section-suffix "beam" :type :double :min 0.001 :default 0.8 :comment "ElectronBomb delayed beam trace step."}
    {:skill-id :electron-bomb :id :beam.max-distance :path "beam.max-distance" :section-suffix "beam" :type :double :min 0.0 :default 30.0 :comment "ElectronBomb delayed beam max distance."}
    {:skill-id :electron-bomb :id :beam.visual-distance :path "beam.visual-distance" :section-suffix "beam" :type :double :min 0.0 :default 28.0 :comment "ElectronBomb delayed beam default visual distance."}
    {:skill-id :electron-bomb :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [250.0 180.0] :comment "ElectronBomb down-stage CP cost."}
    {:skill-id :electron-bomb :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [120.0 90.0] :comment "ElectronBomb down-stage overload cost."}
    {:skill-id :electron-bomb :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [20.0 10.0] :comment "ElectronBomb cooldown ticks."}
    {:skill-id :electron-bomb :id :progression.exp-hit :path "progression.exp-hit" :section-suffix "progression" :type :double :min 0.0 :default 0.003 :comment "ElectronBomb exp gained on beam hit."}

    {:skill-id :electron-missile :id :targeting.seek-range :path "targeting.seek-range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [5.0 13.0] :comment "ElectronMissile target seek range."}
    {:skill-id :electron-missile :id :charge.max-hold-ticks :path "charge.max-hold-ticks" :section-suffix "charge" :type :double-list :min 1.0 :list-count 2 :default [80.0 200.0] :comment "ElectronMissile max hold ticks."}
    {:skill-id :electron-missile :id :timing.fire-interval-ticks :path "timing.fire-interval-ticks" :section-suffix "timing" :type :int :min 1 :default 8 :comment "ElectronMissile ticks between ball launches."}
    {:skill-id :electron-missile :id :projectile.travel-speed :path "projectile.travel-speed" :section-suffix "projectile" :type :double :min 0.001 :default 1.6 :comment "ElectronMissile estimated projectile speed in blocks per tick."}
    {:skill-id :electron-missile :id :projectile.travel-ticks-min :path "projectile.travel-ticks-min" :section-suffix "projectile" :type :int :min 1 :default 2 :comment "ElectronMissile minimum delayed hit ticks."}
    {:skill-id :electron-missile :id :projectile.travel-ticks-max :path "projectile.travel-ticks-max" :section-suffix "projectile" :type :int :min 1 :default 20 :comment "ElectronMissile maximum delayed hit ticks."}
    {:skill-id :electron-missile :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [10.0 18.0] :comment "ElectronMissile damage per ball."}
    {:skill-id :electron-missile :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double :min 0.0 :default 200.0 :comment "ElectronMissile activation overload floor/cost."}
    {:skill-id :electron-missile :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [12.0 5.0] :comment "ElectronMissile channel CP cost."}
    {:skill-id :electron-missile :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [700.0 400.0] :comment "ElectronMissile manual cooldown ticks."}
    {:skill-id :electron-missile :id :progression.exp-hit :path "progression.exp-hit" :section-suffix "progression" :type :double :min 0.0 :default 0.002 :comment "ElectronMissile exp gained per hit."}

    {:skill-id :jet-engine :id :charge.min-ticks :path "charge.min-ticks" :section-suffix "charge" :type :int :min 0 :default 10 :comment "JetEngine minimum charge ticks."}
    {:skill-id :jet-engine :id :charge.max-ticks :path "charge.max-ticks" :section-suffix "charge" :type :int :min 1 :default 80 :comment "JetEngine max charge ticks."}
    {:skill-id :jet-engine :id :movement.speed :path "movement.speed" :section-suffix "movement" :type :double-list :min 0.0 :list-count 2 :default [1.2 2.8] :comment "JetEngine base launch speed."}
    {:skill-id :jet-engine :id :movement.charge-speed-bonus :path "movement.charge-speed-bonus" :section-suffix "movement" :type :double :min 0.0 :default 1.5 :comment "JetEngine full-charge extra speed multiplier."}
    {:skill-id :jet-engine :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [6.0 12.0] :comment "JetEngine collision damage."}
    {:skill-id :jet-engine :id :combat.hit-radius :path "combat.hit-radius" :section-suffix "combat" :type :double :min 0.0 :default 1.5 :comment "JetEngine collision search radius."}
    {:skill-id :jet-engine :id :combat.hit-steps :path "combat.hit-steps" :section-suffix "combat" :type :int :min 1 :default 4 :comment "JetEngine collision samples along launch direction."}
    {:skill-id :jet-engine :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [200.0 150.0] :comment "JetEngine down-stage CP cost."}
    {:skill-id :jet-engine :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [60.0 40.0] :comment "JetEngine down-stage overload cost."}
    {:skill-id :jet-engine :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [60.0 35.0] :comment "JetEngine cooldown ticks."}
    {:skill-id :jet-engine :id :progression.exp-use :path "progression.exp-use" :section-suffix "progression" :type :double :min 0.0 :default 0.002 :comment "JetEngine exp gained on launch."}

    {:skill-id :light-shield :id :combat.damage-reduction :path "combat.damage-reduction" :section-suffix "combat" :type :double-list :min 0.0 :max 1.0 :list-count 2 :default [0.5 0.8] :comment "LightShield incoming damage reduction."}
    {:skill-id :light-shield :id :combat.touch-damage :path "combat.touch-damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [3.0 8.0] :comment "LightShield contact damage."}
    {:skill-id :light-shield :id :combat.touch-radius :path "combat.touch-radius" :section-suffix "combat" :type :double :min 0.0 :default 3.0 :comment "LightShield touch damage radius."}
    {:skill-id :light-shield :id :combat.front-cone-dot :path "combat.front-cone-dot" :section-suffix "combat" :type :double :min -1.0 :max 1.0 :default 0.5 :comment "LightShield minimum dot product for front cone."}
    {:skill-id :light-shield :id :effect.deactivate-slowness-duration-ticks :path "effect.deactivate-slowness-duration-ticks" :section-suffix "effect" :type :int :min 0 :default 60 :comment "LightShield deactivate slowness duration."}
    {:skill-id :light-shield :id :effect.abort-slowness-duration-ticks :path "effect.abort-slowness-duration-ticks" :section-suffix "effect" :type :int :min 0 :default 40 :comment "LightShield abort slowness duration."}
    {:skill-id :light-shield :id :effect.slowness-amplifier :path "effect.slowness-amplifier" :section-suffix "effect" :type :int :min 0 :default 1 :comment "LightShield slowness amplifier."}
    {:skill-id :light-shield :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [200.0 160.0] :comment "LightShield activation CP cost."}
    {:skill-id :light-shield :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [100.0 70.0] :comment "LightShield activation overload cost."}
    {:skill-id :light-shield :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [12.0 8.0] :comment "LightShield tick CP cost."}
    {:skill-id :light-shield :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [100.0 60.0] :comment "LightShield cooldown ticks."}
    {:skill-id :light-shield :id :progression.exp-absorbed-scale :path "progression.exp-absorbed-scale" :section-suffix "progression" :type :double :min 0.0 :default 0.0004 :comment "LightShield exp gained per absorbed damage."}

    {:skill-id :meltdowner :id :charge.min-ticks :path "charge.min-ticks" :section-suffix "charge" :type :int :min 1 :default 20 :comment "Meltdowner minimum charge ticks."}
    {:skill-id :meltdowner :id :charge.max-ticks :path "charge.max-ticks" :section-suffix "charge" :type :int :min 1 :default 40 :comment "Meltdowner optimal max charge ticks."}
    {:skill-id :meltdowner :id :charge.max-tolerant-ticks :path "charge.max-tolerant-ticks" :section-suffix "charge" :type :int :min 1 :default 100 :comment "Meltdowner tolerant max hold ticks."}
    {:skill-id :meltdowner :id :charge.time-rate :path "charge.time-rate" :section-suffix "charge" :type :double-list :min 0.0 :list-count 2 :default [0.8 1.2] :comment "Meltdowner time-rate endpoints between min and max charge."}
    {:skill-id :meltdowner :id :beam.radius :path "beam.radius" :section-suffix "beam" :type :double-list :min 0.0 :list-count 2 :default [2.0 3.0] :comment "Meltdowner beam radius endpoints."}
    {:skill-id :meltdowner :id :beam.query-radius :path "beam.query-radius" :section-suffix "beam" :type :double :min 0.0 :default 30.0 :comment "Meltdowner beam query radius."}
    {:skill-id :meltdowner :id :beam.step :path "beam.step" :section-suffix "beam" :type :double :min 0.001 :default 0.9 :comment "Meltdowner beam trace step."}
    {:skill-id :meltdowner :id :beam.max-distance :path "beam.max-distance" :section-suffix "beam" :type :double :min 0.0 :default 50.0 :comment "Meltdowner beam max distance."}
    {:skill-id :meltdowner :id :beam.visual-distance :path "beam.visual-distance" :section-suffix "beam" :type :double :min 0.0 :default 45.0 :comment "Meltdowner beam visual distance."}
    {:skill-id :meltdowner :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [18.0 50.0] :comment "Meltdowner beam damage endpoints."}
    {:skill-id :meltdowner :id :beam.block-energy :path "beam.block-energy" :section-suffix "beam" :type :double-list :min 0.0 :list-count 2 :default [300.0 700.0] :comment "Meltdowner block break energy endpoints."}
    {:skill-id :meltdowner :id :reflection.cp-per-damage :path "reflection.cp-per-damage" :section-suffix "reflection" :type :double-list :min 0.0 :list-count 2 :default [20.0 15.0] :comment "VecReflection CP cost per incoming Meltdowner damage."}
    {:skill-id :meltdowner :id :reflection.shot-distance :path "reflection.shot-distance" :section-suffix "reflection" :type :double :min 0.0 :default 10.0 :comment "Meltdowner reflected shot distance."}
    {:skill-id :meltdowner :id :reflection.damage-multiplier :path "reflection.damage-multiplier" :section-suffix "reflection" :type :double :min 0.0 :default 0.5 :comment "Meltdowner reflected shot damage multiplier."}
    {:skill-id :meltdowner :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [200.0 170.0] :comment "Meltdowner down-stage overload cost."}
    {:skill-id :meltdowner :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [10.0 15.0] :comment "Meltdowner tick CP cost."}
    {:skill-id :meltdowner :id :cooldown.base-multiplier :path "cooldown.base-multiplier" :section-suffix "cooldown" :type :double :min 0.0 :default 20.0 :comment "Meltdowner cooldown base multiplier."}
    {:skill-id :meltdowner :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [15.0 7.0] :comment "Meltdowner cooldown endpoints multiplied by time-rate/base."}
    {:skill-id :meltdowner :id :progression.exp-use :path "progression.exp-use" :section-suffix "progression" :type :double :min 0.0 :default 0.002 :comment "Meltdowner exp gained per performed beam scaled by time-rate."}

    {:skill-id :mine-ray-basic :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [8.0 12.0] :comment "MineRayBasic range."}
    {:skill-id :mine-ray-basic :id :mining.break-speed :path "mining.break-speed" :section-suffix "mining" :type :double-list :min 0.0 :list-count 2 :default [0.15 0.35] :comment "MineRayBasic break speed."}
    {:skill-id :mine-ray-basic :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [60.0 40.0] :comment "MineRayBasic down-stage overload cost."}
    {:skill-id :mine-ray-basic :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [12.0 8.0] :comment "MineRayBasic tick CP cost."}
    {:skill-id :mine-ray-basic :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :int :min 0 :default 5 :comment "MineRayBasic cooldown ticks."}
    {:skill-id :mine-ray-basic :id :progression.exp-block :path "progression.exp-block" :section-suffix "progression" :type :double :min 0.0 :default 0.001 :comment "MineRayBasic exp per block broken."}
    {:skill-id :mine-ray-expert :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [16.0 22.0] :comment "MineRayExpert range."}
    {:skill-id :mine-ray-expert :id :mining.break-speed :path "mining.break-speed" :section-suffix "mining" :type :double-list :min 0.0 :list-count 2 :default [0.4 0.8] :comment "MineRayExpert break speed."}
    {:skill-id :mine-ray-expert :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [80.0 55.0] :comment "MineRayExpert down-stage overload cost."}
    {:skill-id :mine-ray-expert :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [18.0 12.0] :comment "MineRayExpert tick CP cost."}
    {:skill-id :mine-ray-expert :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :int :min 0 :default 5 :comment "MineRayExpert cooldown ticks."}
    {:skill-id :mine-ray-expert :id :progression.exp-block :path "progression.exp-block" :section-suffix "progression" :type :double :min 0.0 :default 0.001 :comment "MineRayExpert exp per block broken."}
    {:skill-id :mine-ray-luck :id :targeting.range :path "targeting.range" :section-suffix "targeting" :type :double-list :min 0.0 :list-count 2 :default [16.0 22.0] :comment "MineRayLuck range."}
    {:skill-id :mine-ray-luck :id :mining.break-speed :path "mining.break-speed" :section-suffix "mining" :type :double-list :min 0.0 :list-count 2 :default [0.5 1.0] :comment "MineRayLuck break speed."}
    {:skill-id :mine-ray-luck :id :effect.extra-drop-chance :path "effect.extra-drop-chance" :section-suffix "effect" :type :double :min 0.0 :max 1.0 :default 0.33 :comment "MineRayLuck extra drop chance."}
    {:skill-id :mine-ray-luck :id :effect.extra-drop-y-offset :path "effect.extra-drop-y-offset" :section-suffix "effect" :type :int :default -999 :comment "MineRayLuck synthetic Y offset used for bonus drop hook."}
    {:skill-id :mine-ray-luck :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [100.0 70.0] :comment "MineRayLuck down-stage overload cost."}
    {:skill-id :mine-ray-luck :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [22.0 15.0] :comment "MineRayLuck tick CP cost."}
    {:skill-id :mine-ray-luck :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :int :min 0 :default 5 :comment "MineRayLuck cooldown ticks."}
    {:skill-id :mine-ray-luck :id :progression.exp-block :path "progression.exp-block" :section-suffix "progression" :type :double :min 0.0 :default 0.002 :comment "MineRayLuck exp per block broken."}

    {:skill-id :ray-barrage :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [4.0 10.0] :comment "RayBarrage damage per beam."}
    {:skill-id :ray-barrage :id :beam.count :path "beam.count" :section-suffix "beam" :type :int :min 1 :default 5 :comment "RayBarrage number of beams."}
    {:skill-id :ray-barrage :id :beam.spread :path "beam.spread" :section-suffix "beam" :type :double :min 0.0 :default 0.18 :comment "RayBarrage random cone spread."}
    {:skill-id :ray-barrage :id :beam.radius :path "beam.radius" :section-suffix "beam" :type :double :min 0.0 :default 0.3 :comment "RayBarrage beam radius."}
    {:skill-id :ray-barrage :id :beam.query-radius :path "beam.query-radius" :section-suffix "beam" :type :double :min 0.0 :default 20.0 :comment "RayBarrage beam query radius."}
    {:skill-id :ray-barrage :id :beam.step :path "beam.step" :section-suffix "beam" :type :double :min 0.001 :default 0.8 :comment "RayBarrage beam step."}
    {:skill-id :ray-barrage :id :beam.max-distance :path "beam.max-distance" :section-suffix "beam" :type :double :min 0.0 :default 22.0 :comment "RayBarrage beam max distance."}
    {:skill-id :ray-barrage :id :beam.visual-distance :path "beam.visual-distance" :section-suffix "beam" :type :double :min 0.0 :default 20.0 :comment "RayBarrage beam visual distance."}
    {:skill-id :ray-barrage :id :cost.down.cp :path "cost.down.cp" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [300.0 220.0] :comment "RayBarrage down-stage CP cost."}
    {:skill-id :ray-barrage :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [130.0 100.0] :comment "RayBarrage down-stage overload cost."}
    {:skill-id :ray-barrage :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [40.0 25.0] :comment "RayBarrage cooldown ticks."}
    {:skill-id :ray-barrage :id :progression.exp-hit :path "progression.exp-hit" :section-suffix "progression" :type :double :min 0.0 :default 0.003 :comment "RayBarrage exp gained when any beam hits."}

    {:skill-id :scatter-bomb :id :projectile.max-balls :path "projectile.max-balls" :section-suffix "projectile" :type :int :min 1 :default 6 :comment "ScatterBomb maximum accumulated balls."}
    {:skill-id :scatter-bomb :id :projectile.spawn-interval-ticks :path "projectile.spawn-interval-ticks" :section-suffix "projectile" :type :int :min 1 :default 10 :comment "ScatterBomb ball spawn interval."}
    {:skill-id :scatter-bomb :id :projectile.spawn-start-tick :path "projectile.spawn-start-tick" :section-suffix "projectile" :type :int :min 0 :default 20 :comment "ScatterBomb first ball spawn tick."}
    {:skill-id :scatter-bomb :id :projectile.cone-spread :path "projectile.cone-spread" :section-suffix "projectile" :type :double-list :min 0.0 :list-count 2 :default [0.3 0.8] :comment "ScatterBomb random cone spread [min,max]."}
    {:skill-id :scatter-bomb :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [4.0 9.0] :comment "ScatterBomb beam damage."}
    {:skill-id :scatter-bomb :id :beam.radius :path "beam.radius" :section-suffix "beam" :type :double :min 0.0 :default 0.3 :comment "ScatterBomb delayed beam radius."}
    {:skill-id :scatter-bomb :id :beam.query-radius :path "beam.query-radius" :section-suffix "beam" :type :double :min 0.0 :default 20.0 :comment "ScatterBomb delayed beam query radius."}
    {:skill-id :scatter-bomb :id :beam.step :path "beam.step" :section-suffix "beam" :type :double :min 0.001 :default 0.8 :comment "ScatterBomb delayed beam trace step."}
    {:skill-id :scatter-bomb :id :beam.max-distance :path "beam.max-distance" :section-suffix "beam" :type :double :min 0.0 :default 25.0 :comment "ScatterBomb delayed beam max distance."}
    {:skill-id :scatter-bomb :id :beam.visual-distance :path "beam.visual-distance" :section-suffix "beam" :type :double :min 0.0 :default 23.0 :comment "ScatterBomb delayed beam visual distance."}
    {:skill-id :scatter-bomb :id :effect.anti-afk-tick :path "effect.anti-afk-tick" :section-suffix "effect" :type :int :min 1 :default 200 :comment "ScatterBomb anti-AFK self-damage tick."}
    {:skill-id :scatter-bomb :id :effect.anti-afk-damage :path "effect.anti-afk-damage" :section-suffix "effect" :type :double :min 0.0 :default 6.0 :comment "ScatterBomb anti-AFK self-damage amount."}
    {:skill-id :scatter-bomb :id :cost.down.overload :path "cost.down.overload" :section-suffix "cost.down" :type :double-list :min 0.0 :list-count 2 :default [150.0 120.0] :comment "ScatterBomb down-stage overload cost."}
    {:skill-id :scatter-bomb :id :cost.overload-floor-scale :path "cost.overload-floor-scale" :section-suffix "cost" :type :double :min 0.0 :default 0.8 :comment "ScatterBomb overload floor scale from activation overload."}
    {:skill-id :scatter-bomb :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [8.0 5.0] :comment "ScatterBomb tick CP cost."}
    {:skill-id :scatter-bomb :id :cooldown.ticks-per-ball :path "cooldown.ticks-per-ball" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [30.0 15.0] :comment "ScatterBomb cooldown ticks per ball."}
    {:skill-id :scatter-bomb :id :progression.exp-per-ball :path "progression.exp-per-ball" :section-suffix "progression" :type :double :min 0.0 :default 0.002 :comment "ScatterBomb exp gained per fired ball."}])

(def ^:private vecmanip-skill-tunable-definitions
  [

    {:skill-id :directed-shock :id :charge.min-ticks :path "charge.min-ticks" :section-suffix "charge" :type :int :min 0 :default 6 :comment "DirectedShock minimum valid charge ticks."}
    {:skill-id :directed-shock :id :charge.max-accepted-ticks :path "charge.max-accepted-ticks" :section-suffix "charge" :type :int :min 1 :default 50 :comment "DirectedShock maximum accepted release charge ticks."}
    {:skill-id :directed-shock :id :charge.max-tolerant-ticks :path "charge.max-tolerant-ticks" :section-suffix "charge" :type :int :min 1 :default 200 :comment "DirectedShock charge ticks before auto-abort."}
    {:skill-id :directed-shock :id :targeting.raycast-distance :path "targeting.raycast-distance" :section-suffix "targeting" :type :double :min 0.0 :default 3.0 :comment "DirectedShock entity raycast distance."}
    {:skill-id :directed-shock :id :targeting.eye-height :path "targeting.eye-height" :section-suffix "targeting" :type :double :min 0.0 :default 1.62 :comment "DirectedShock target eye height fallback."}
    {:skill-id :directed-shock :id :movement.hit-impulse :path "movement.hit-impulse" :section-suffix "movement" :type :double :min 0.0 :default 0.24 :comment "DirectedShock forward hit impulse scale."}
    {:skill-id :directed-shock :id :movement.knockback-y-adjust :path "movement.knockback-y-adjust" :section-suffix "movement" :type :double :default 0.6 :comment "DirectedShock vertical adjustment before knockback normalization."}
    {:skill-id :directed-shock :id :movement.knockback-scale :path "movement.knockback-scale" :section-suffix "movement" :type :double :default -0.7 :comment "DirectedShock knockback velocity scale."}
    {:skill-id :directed-shock :id :movement.knockback-exp-threshold :path "movement.knockback-exp-threshold" :section-suffix "movement" :type :double :min 0.0 :max 1.0 :default 0.25 :comment "DirectedShock exp threshold required for reverse knockback."}
    {:skill-id :directed-shock :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [7.0 15.0] :comment "DirectedShock hit damage endpoints."}
    {:skill-id :directed-shock :id :cost.up.cp :path "cost.up.cp" :section-suffix "cost.up" :type :double-list :min 0.0 :list-count 2 :default [50.0 100.0] :comment "DirectedShock up-stage CP cost."}
    {:skill-id :directed-shock :id :cost.up.overload :path "cost.up.overload" :section-suffix "cost.up" :type :double-list :min 0.0 :list-count 2 :default [18.0 12.0] :comment "DirectedShock up-stage overload cost."}
    {:skill-id :directed-shock :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [60.0 20.0] :comment "DirectedShock cooldown ticks."}
    {:skill-id :directed-shock :id :progression.exp-hit :path "progression.exp-hit" :section-suffix "progression" :type :double :min 0.0 :default 0.0035 :comment "DirectedShock exp gained on hit."}
    {:skill-id :directed-shock :id :progression.exp-miss :path "progression.exp-miss" :section-suffix "progression" :type :double :min 0.0 :default 0.001 :comment "DirectedShock exp gained on miss."}

    {:skill-id :vec-accel :id :charge.max-ticks :path "charge.max-ticks" :section-suffix "charge" :type :int :min 1 :default 20 :comment "VecAccel maximum charge ticks."}
    {:skill-id :vec-accel :id :movement.max-velocity :path "movement.max-velocity" :section-suffix "movement" :type :double :min 0.0 :default 2.5 :comment "VecAccel maximum launch velocity."}
    {:skill-id :vec-accel :id :movement.speed-progress :path "movement.speed-progress" :section-suffix "movement" :type :double-list :list-count 2 :default [0.4 1.0] :comment "VecAccel sine-progress endpoints used by charge speed."}
    {:skill-id :vec-accel :id :movement.pitch-offset-radians :path "movement.pitch-offset-radians" :section-suffix "movement" :type :double :default -0.174533 :comment "VecAccel pitch offset in radians."}
    {:skill-id :vec-accel :id :targeting.ground-check-distance :path "targeting.ground-check-distance" :section-suffix "targeting" :type :double :min 0.0 :default 2.0 :comment "VecAccel downward raycast distance for low-exp ground checks."}
    {:skill-id :vec-accel :id :targeting.groundless-exp-threshold :path "targeting.groundless-exp-threshold" :section-suffix "targeting" :type :double :min 0.0 :max 1.0 :default 0.5 :comment "VecAccel exp threshold that allows launches without ground contact."}
    {:skill-id :vec-accel :id :cost.up.cp :path "cost.up.cp" :section-suffix "cost.up" :type :double-list :min 0.0 :list-count 2 :default [120.0 80.0] :comment "VecAccel up-stage CP cost."}
    {:skill-id :vec-accel :id :cost.up.overload :path "cost.up.overload" :section-suffix "cost.up" :type :double-list :min 0.0 :list-count 2 :default [30.0 15.0] :comment "VecAccel up-stage overload cost."}
    {:skill-id :vec-accel :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [80.0 50.0] :comment "VecAccel manual cooldown ticks."}
    {:skill-id :vec-accel :id :progression.exp-use :path "progression.exp-use" :section-suffix "progression" :type :double :min 0.0 :default 0.002 :comment "VecAccel exp gained on launch."}

    {:skill-id :directed-blastwave :id :charge.min-ticks :path "charge.min-ticks" :section-suffix "charge" :type :int :min 0 :default 6 :comment "DirectedBlastwave minimum valid charge ticks."}
    {:skill-id :directed-blastwave :id :charge.max-accepted-ticks :path "charge.max-accepted-ticks" :section-suffix "charge" :type :int :min 1 :default 50 :comment "DirectedBlastwave maximum accepted release charge ticks."}
    {:skill-id :directed-blastwave :id :charge.max-tolerant-ticks :path "charge.max-tolerant-ticks" :section-suffix "charge" :type :int :min 1 :default 200 :comment "DirectedBlastwave charge ticks before auto-abort."}
    {:skill-id :directed-blastwave :id :charge.punch-anim-ticks :path "charge.punch-anim-ticks" :section-suffix "charge" :type :int :min 0 :default 6 :comment "DirectedBlastwave punch animation ticks before termination."}
    {:skill-id :directed-blastwave :id :targeting.raycast-distance :path "targeting.raycast-distance" :section-suffix "targeting" :type :double :min 0.0 :default 4.0 :comment "DirectedBlastwave raycast distance."}
    {:skill-id :directed-blastwave :id :targeting.eye-height :path "targeting.eye-height" :section-suffix "targeting" :type :double :min 0.0 :default 1.62 :comment "DirectedBlastwave target eye height fallback."}
    {:skill-id :directed-blastwave :id :combat.aoe-radius :path "combat.aoe-radius" :section-suffix "combat" :type :double :min 0.0 :default 3.0 :comment "DirectedBlastwave entity AOE radius."}
    {:skill-id :directed-blastwave :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [10.0 25.0] :comment "DirectedBlastwave AOE damage."}
    {:skill-id :directed-blastwave :id :movement.knockback-y-adjust :path "movement.knockback-y-adjust" :section-suffix "movement" :type :double :default 0.4 :comment "DirectedBlastwave vertical adjustment before knockback normalization."}
    {:skill-id :directed-blastwave :id :movement.knockback-scale :path "movement.knockback-scale" :section-suffix "movement" :type :double :default -1.2 :comment "DirectedBlastwave knockback impulse scale."}
    {:skill-id :directed-blastwave :id :breaking.hardness-low-threshold :path "breaking.hardness-low-threshold" :section-suffix "breaking" :type :double :min 0.0 :max 1.0 :default 0.25 :comment "DirectedBlastwave exp threshold for low hardness cap."}
    {:skill-id :directed-blastwave :id :breaking.hardness-mid-threshold :path "breaking.hardness-mid-threshold" :section-suffix "breaking" :type :double :min 0.0 :max 1.0 :default 0.5 :comment "DirectedBlastwave exp threshold for mid hardness cap."}
    {:skill-id :directed-blastwave :id :breaking.hardness-caps :path "breaking.hardness-caps" :section-suffix "breaking" :type :double-list :min 0.0 :list-count 3 :default [2.9 25.0 55.0] :comment "DirectedBlastwave block hardness caps for low, mid, and high exp."}
    {:skill-id :directed-blastwave :id :breaking.break-probability :path "breaking.break-probability" :section-suffix "breaking" :type :double-list :min 0.0 :max 1.0 :list-count 2 :default [0.5 0.8] :comment "DirectedBlastwave random block break probability."}
    {:skill-id :directed-blastwave :id :breaking.drop-probability :path "breaking.drop-probability" :section-suffix "breaking" :type :double-list :min 0.0 :max 1.0 :list-count 2 :default [0.4 0.9] :comment "DirectedBlastwave block drop probability."}
    {:skill-id :directed-blastwave :id :cost.up.cp :path "cost.up.cp" :section-suffix "cost.up" :type :double-list :min 0.0 :list-count 2 :default [160.0 200.0] :comment "DirectedBlastwave up-stage CP cost."}
    {:skill-id :directed-blastwave :id :cost.up.overload :path "cost.up.overload" :section-suffix "cost.up" :type :double-list :min 0.0 :list-count 2 :default [50.0 30.0] :comment "DirectedBlastwave up-stage overload cost."}
    {:skill-id :directed-blastwave :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [80.0 50.0] :comment "DirectedBlastwave cooldown ticks."}
    {:skill-id :directed-blastwave :id :progression.exp-hit :path "progression.exp-hit" :section-suffix "progression" :type :double :min 0.0 :default 0.0025 :comment "DirectedBlastwave exp gained when entities are hit."}
    {:skill-id :directed-blastwave :id :progression.exp-miss :path "progression.exp-miss" :section-suffix "progression" :type :double :min 0.0 :default 0.0012 :comment "DirectedBlastwave exp gained when no entities are hit."}

    {:skill-id :groundshock :id :charge.min-ticks :path "charge.min-ticks" :section-suffix "charge" :type :int :min 0 :default 5 :comment "Groundshock minimum charge ticks."}
    {:skill-id :groundshock :id :cost.up.cp :path "cost.up.cp" :section-suffix "cost.up" :type :double-list :min 0.0 :list-count 2 :default [80.0 150.0] :comment "Groundshock up-stage CP cost."}
    {:skill-id :groundshock :id :cost.up.overload :path "cost.up.overload" :section-suffix "cost.up" :type :double-list :min 0.0 :list-count 2 :default [15.0 10.0] :comment "Groundshock up-stage overload cost."}
    {:skill-id :groundshock :id :effect.init-energy :path "effect.init-energy" :section-suffix "effect" :type :double-list :min 0.0 :list-count 2 :default [60.0 120.0] :comment "Groundshock initial propagation energy."}
    {:skill-id :groundshock :id :effect.max-iterations :path "effect.max-iterations" :section-suffix "effect" :type :double-list :min 1.0 :list-count 2 :default [10.0 25.0] :comment "Groundshock maximum propagation iterations."}
    {:skill-id :groundshock :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [4.0 6.0] :comment "Groundshock AOE damage."}
    {:skill-id :groundshock :id :combat.entity-search-radius :path "combat.entity-search-radius" :section-suffix "combat" :type :double :min 0.0 :default 2.0 :comment "Groundshock entity search radius per affected block."}
    {:skill-id :groundshock :id :movement.launch-random-base :path "movement.launch-random-base" :section-suffix "movement" :type :double :min 0.0 :default 0.6 :comment "Groundshock launch base Y velocity."}
    {:skill-id :groundshock :id :movement.launch-random-span :path "movement.launch-random-span" :section-suffix "movement" :type :double :min 0.0 :default 0.3 :comment "Groundshock random launch Y span."}
    {:skill-id :groundshock :id :movement.launch-scale :path "movement.launch-scale" :section-suffix "movement" :type :double-list :min 0.0 :list-count 2 :default [0.8 1.3] :comment "Groundshock launch scale endpoints."}
    {:skill-id :groundshock :id :breaking.ground-break-probability :path "breaking.ground-break-probability" :section-suffix "breaking" :type :double :min 0.0 :max 1.0 :default 0.3 :comment "Groundshock chance to break a ground block in the path."}
    {:skill-id :groundshock :id :breaking.drop-rate :path "breaking.drop-rate" :section-suffix "breaking" :type :double-list :min 0.0 :max 1.0 :list-count 2 :default [0.3 1.0] :comment "Groundshock block drop rate endpoints."}
    {:skill-id :groundshock :id :breaking.mastery-exp-threshold :path "breaking.mastery-exp-threshold" :section-suffix "breaking" :type :double :min 0.0 :max 1.0 :default 1.0 :comment "Groundshock exp threshold for the mastery break ring."}
    {:skill-id :groundshock :id :breaking.mastery-radius :path "breaking.mastery-radius" :section-suffix "breaking" :type :int :min 0 :default 5 :comment "Groundshock mastery ring radius in blocks."}
    {:skill-id :groundshock :id :breaking.mastery-hardness-cap :path "breaking.mastery-hardness-cap" :section-suffix "breaking" :type :double :min 0.0 :default 0.6 :comment "Groundshock mastery ring max hardness."}
    {:skill-id :groundshock :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [80.0 40.0] :comment "Groundshock cooldown ticks."}
    {:skill-id :groundshock :id :progression.exp-entity :path "progression.exp-entity" :section-suffix "progression" :type :double :min 0.0 :default 0.002 :comment "Groundshock exp gained per affected entity."}
    {:skill-id :groundshock :id :progression.exp-use :path "progression.exp-use" :section-suffix "progression" :type :double :min 0.0 :default 0.001 :comment "Groundshock exp gained per successful use."}

    {:skill-id :blood-retrograde :id :charge.max-ticks :path "charge.max-ticks" :section-suffix "charge" :type :int :min 1 :default 30 :comment "BloodRetrograde max charge ticks before auto-release."}
    {:skill-id :blood-retrograde :id :charge.fx-ratio-ticks :path "charge.fx-ratio-ticks" :section-suffix "charge" :type :double :min 0.001 :default 20.0 :comment "BloodRetrograde charge ticks mapped to full FX ratio."}
    {:skill-id :blood-retrograde :id :targeting.distance :path "targeting.distance" :section-suffix "targeting" :type :double :min 0.0 :default 2.0 :comment "BloodRetrograde entity raycast distance."}
    {:skill-id :blood-retrograde :id :targeting.entity-search-radius :path "targeting.entity-search-radius" :section-suffix "targeting" :type :double :min 0.0 :default 4.0 :comment "BloodRetrograde target info refresh radius."}
    {:skill-id :blood-retrograde :id :targeting.fallback-width :path "targeting.fallback-width" :section-suffix "targeting" :type :double :min 0.0 :default 0.6 :comment "BloodRetrograde fallback entity width."}
    {:skill-id :blood-retrograde :id :targeting.fallback-height :path "targeting.fallback-height" :section-suffix "targeting" :type :double :min 0.0 :default 1.8 :comment "BloodRetrograde fallback entity height."}
    {:skill-id :blood-retrograde :id :targeting.fallback-eye-height :path "targeting.fallback-eye-height" :section-suffix "targeting" :type :double :min 0.0 :default 1.62 :comment "BloodRetrograde fallback entity eye height."}
    {:skill-id :blood-retrograde :id :effect.spray-angles :path "effect.spray-angles" :section-suffix "effect" :type :double-list :list-count 9 :default [0.0 30.0 45.0 60.0 80.0 -30.0 -45.0 -60.0 -80.0] :comment "BloodRetrograde spray pitch angles."}
    {:skill-id :blood-retrograde :id :cost.release.cp :path "cost.release.cp" :section-suffix "cost.release" :type :double-list :min 0.0 :list-count 2 :default [280.0 350.0] :comment "BloodRetrograde release CP cost."}
    {:skill-id :blood-retrograde :id :cost.release.overload :path "cost.release.overload" :section-suffix "cost.release" :type :double-list :min 0.0 :list-count 2 :default [55.0 40.0] :comment "BloodRetrograde release overload cost."}
    {:skill-id :blood-retrograde :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [30.0 60.0] :comment "BloodRetrograde direct damage."}
    {:skill-id :blood-retrograde :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [90.0 40.0] :comment "BloodRetrograde cooldown ticks."}
    {:skill-id :blood-retrograde :id :progression.exp-hit :path "progression.exp-hit" :section-suffix "progression" :type :double :min 0.0 :default 0.002 :comment "BloodRetrograde exp gained on hit."}

    {:skill-id :plasma-cannon :id :charge.time :path "charge.time" :section-suffix "charge" :type :double-list :min 1.0 :list-count 2 :default [60.0 30.0] :comment "PlasmaCannon charge time endpoints."}
    {:skill-id :plasma-cannon :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [18.0 25.0] :comment "PlasmaCannon charge CP per tick."}
    {:skill-id :plasma-cannon :id :cost.overload-keep :path "cost.overload-keep" :section-suffix "cost" :type :double-list :min 0.0 :list-count 2 :default [500.0 400.0] :comment "PlasmaCannon overload floor/activation cost."}
    {:skill-id :plasma-cannon :id :targeting.raycast-distance :path "targeting.raycast-distance" :section-suffix "targeting" :type :double :min 0.0 :default 100.0 :comment "PlasmaCannon destination raycast distance."}
    {:skill-id :plasma-cannon :id :targeting.eye-height :path "targeting.eye-height" :section-suffix "targeting" :type :double :min 0.0 :default 1.62 :comment "PlasmaCannon raycast eye height."}
    {:skill-id :plasma-cannon :id :projectile.spawn-y-offset :path "projectile.spawn-y-offset" :section-suffix "projectile" :type :double :default 15.0 :comment "PlasmaCannon projectile spawn Y offset."}
    {:skill-id :plasma-cannon :id :projectile.block-hit-extra-distance :path "projectile.block-hit-extra-distance" :section-suffix "projectile" :type :double :min 0.0 :default 0.1 :comment "PlasmaCannon extra block raycast distance per step."}
    {:skill-id :plasma-cannon :id :projectile.destination-epsilon :path "projectile.destination-epsilon" :section-suffix "projectile" :type :double :min 0.0 :default 1.5 :comment "PlasmaCannon distance to destination that triggers explosion."}
    {:skill-id :plasma-cannon :id :projectile.max-flight-ticks :path "projectile.max-flight-ticks" :section-suffix "projectile" :type :int :min 1 :default 240 :comment "PlasmaCannon max projectile flight ticks."}
    {:skill-id :plasma-cannon :id :projectile.sync-interval-ticks :path "projectile.sync-interval-ticks" :section-suffix "projectile" :type :int :min 1 :default 5 :comment "PlasmaCannon projectile FX sync interval."}
    {:skill-id :plasma-cannon :id :combat.damage :path "combat.damage" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [80.0 150.0] :comment "PlasmaCannon explosion damage."}
    {:skill-id :plasma-cannon :id :combat.damage-radius :path "combat.damage-radius" :section-suffix "combat" :type :double :min 0.0 :default 10.0 :comment "PlasmaCannon entity damage radius."}
    {:skill-id :plasma-cannon :id :combat.explosion-radius :path "combat.explosion-radius" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [12.0 15.0] :comment "PlasmaCannon terrain explosion radius."}
    {:skill-id :plasma-cannon :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [1000.0 600.0] :comment "PlasmaCannon cooldown ticks."}
    {:skill-id :plasma-cannon :id :progression.exp-use :path "progression.exp-use" :section-suffix "progression" :type :double :min 0.0 :default 0.008 :comment "PlasmaCannon exp gained on successful explosion."}

    {:skill-id :storm-wing :id :charge.time :path "charge.time" :section-suffix "charge" :type :double-list :min 1.0 :list-count 2 :default [70.0 30.0] :comment "StormWing charge time endpoints."}
    {:skill-id :storm-wing :id :movement.acceleration :path "movement.acceleration" :section-suffix "movement" :type :double :min 0.0 :default 0.16 :comment "StormWing velocity acceleration step."}
    {:skill-id :storm-wing :id :movement.speed-exp-threshold :path "movement.speed-exp-threshold" :section-suffix "movement" :type :double :min 0.0 :max 1.0 :default 0.45 :comment "StormWing exp threshold for high speed multiplier."}
    {:skill-id :storm-wing :id :movement.speed-multipliers :path "movement.speed-multipliers" :section-suffix "movement" :type :double-list :min 0.0 :list-count 2 :default [0.7 1.2] :comment "StormWing low/high speed multipliers."}
    {:skill-id :storm-wing :id :movement.speed-scale :path "movement.speed-scale" :section-suffix "movement" :type :double-list :min 0.0 :list-count 2 :default [2.0 3.0] :comment "StormWing exp-scaled speed factor."}
    {:skill-id :storm-wing :id :movement.hover-near-ground-velocity :path "movement.hover-near-ground-velocity" :section-suffix "movement" :type :double :default 0.1 :comment "StormWing hover Y velocity near ground."}
    {:skill-id :storm-wing :id :movement.hover-air-velocity :path "movement.hover-air-velocity" :section-suffix "movement" :type :double :default 0.078 :comment "StormWing hover Y velocity in air."}
    {:skill-id :storm-wing :id :targeting.near-ground-eye-height :path "targeting.near-ground-eye-height" :section-suffix "targeting" :type :double :min 0.0 :default 1.62 :comment "StormWing near-ground raycast eye height."}
    {:skill-id :storm-wing :id :targeting.near-ground-distance :path "targeting.near-ground-distance" :section-suffix "targeting" :type :double :min 0.0 :default 2.0 :comment "StormWing near-ground raycast distance."}
    {:skill-id :storm-wing :id :breaking.low-exp-threshold :path "breaking.low-exp-threshold" :section-suffix "breaking" :type :double :min 0.0 :max 1.0 :default 0.15 :comment "StormWing exp threshold below which soft blocks are broken."}
    {:skill-id :storm-wing :id :breaking.soft-block-tries :path "breaking.soft-block-tries" :section-suffix "breaking" :type :int :min 0 :default 40 :comment "StormWing random soft-block break attempts per tick."}
    {:skill-id :storm-wing :id :breaking.soft-block-search-radius :path "breaking.soft-block-search-radius" :section-suffix "breaking" :type :int :min 0 :default 10 :comment "StormWing random soft-block search radius."}
    {:skill-id :storm-wing :id :breaking.soft-hardness-max :path "breaking.soft-hardness-max" :section-suffix "breaking" :type :double :min 0.0 :default 0.3 :comment "StormWing max hardness for random soft-block breaking."}
    {:skill-id :storm-wing :id :combat.mastery-knockback-radius :path "combat.mastery-knockback-radius" :section-suffix "combat" :type :double :min 0.0 :default 3.0 :comment "StormWing max-exp initial knockback radius."}
    {:skill-id :storm-wing :id :combat.mastery-knockback-strength :path "combat.mastery-knockback-strength" :section-suffix "combat" :type :double :min 0.0 :default 2.0 :comment "StormWing max-exp initial knockback strength."}
    {:skill-id :storm-wing :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [40.0 25.0] :comment "StormWing active tick CP cost."}
    {:skill-id :storm-wing :id :cost.tick.overload :path "cost.tick.overload" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [10.0 7.0] :comment "StormWing active tick overload cost."}
    {:skill-id :storm-wing :id :cooldown.ticks :path "cooldown.ticks" :section-suffix "cooldown" :type :double-list :min 0.0 :list-count 2 :default [30.0 10.0] :comment "StormWing cooldown ticks on terminate."}
    {:skill-id :storm-wing :id :progression.exp-tick :path "progression.exp-tick" :section-suffix "progression" :type :double :min 0.0 :default 0.00005 :comment "StormWing exp gained per flight tick."}

    {:skill-id :vec-deviation :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [13.0 5.0] :comment "VecDeviation active tick CP cost."}
    {:skill-id :vec-deviation :id :targeting.radius :path "targeting.radius" :section-suffix "targeting" :type :double :min 0.0 :default 5.0 :comment "VecDeviation projectile scan radius."}
    {:skill-id :vec-deviation :id :targeting.affected-entity-difficulty :path "targeting.affected-entity-difficulty" :section-suffix "targeting" :type :string-list :default ["minecraft:arrow:1.0" "minecraft:potion:1.4" "minecraft:snowball:0.1"] :comment "VecDeviation projectile difficulty entries as registry-id:difficulty."}
    {:skill-id :vec-deviation :id :targeting.excluded-entity-ids :path "targeting.excluded-entity-ids" :section-suffix "targeting" :type :string-list :default ["minecraft:item" "minecraft:xp_bottle" "minecraft:experience_bottle"] :comment "VecDeviation registry ids excluded from projectile handling."}
    {:skill-id :vec-deviation :id :targeting.large-fireball-ids :path "targeting.large-fireball-ids" :section-suffix "targeting" :type :string-list :default ["minecraft:fireball" "minecraft:large_fireball"] :comment "VecDeviation large fireball ids that create a small explosion when discarded."}
    {:skill-id :vec-deviation :id :targeting.small-fireball-ids :path "targeting.small-fireball-ids" :section-suffix "targeting" :type :string-list :default ["minecraft:small_fireball"] :comment "VecDeviation small fireball ids that are discarded when deflected."}
    {:skill-id :vec-deviation :id :cost.deflect.cp :path "cost.deflect.cp" :section-suffix "cost.deflect" :type :double-list :min 0.0 :list-count 2 :default [15.0 12.0] :comment "VecDeviation CP cost per projectile difficulty."}
    {:skill-id :vec-deviation :id :combat.fireball-explosion-radius :path "combat.fireball-explosion-radius" :section-suffix "combat" :type :double :min 0.0 :default 1.0 :comment "VecDeviation explosion radius for reflected large fireballs."}
    {:skill-id :vec-deviation :id :combat.damage-ignore-threshold :path "combat.damage-ignore-threshold" :section-suffix "combat" :type :double :min 0.0 :default 9999.0 :comment "VecDeviation damage values above this threshold are not reduced."}
    {:skill-id :vec-deviation :id :combat.damage-reduction :path "combat.damage-reduction" :section-suffix "combat" :type :double-list :min 0.0 :max 1.0 :list-count 2 :default [0.4 0.9] :comment "VecDeviation incoming damage reduction rate."}
    {:skill-id :vec-deviation :id :cost.damage.cp :path "cost.damage.cp" :section-suffix "cost.damage" :type :double-list :min 0.0 :list-count 2 :default [15.0 12.0] :comment "VecDeviation max CP consumed per incoming damage event."}
    {:skill-id :vec-deviation :id :progression.exp-deflect-scale :path "progression.exp-deflect-scale" :section-suffix "progression" :type :double :min 0.0 :default 0.001 :comment "VecDeviation exp gained per projectile difficulty."}
    {:skill-id :vec-deviation :id :progression.exp-damage-scale :path "progression.exp-damage-scale" :section-suffix "progression" :type :double :min 0.0 :default 0.0006 :comment "VecDeviation exp gained per reduced damage."}

    {:skill-id :vec-reflection :id :cost.tick.cp :path "cost.tick.cp" :section-suffix "cost.tick" :type :double-list :min 0.0 :list-count 2 :default [15.0 11.0] :comment "VecReflection active tick CP cost."}
    {:skill-id :vec-reflection :id :cost.overload-keep :path "cost.overload-keep" :section-suffix "cost" :type :double-list :min 0.0 :list-count 2 :default [350.0 250.0] :comment "VecReflection overload floor while active."}
    {:skill-id :vec-reflection :id :targeting.radius :path "targeting.radius" :section-suffix "targeting" :type :double :min 0.0 :default 4.0 :comment "VecReflection projectile scan radius."}
    {:skill-id :vec-reflection :id :targeting.attacker-search-radius :path "targeting.attacker-search-radius" :section-suffix "targeting" :type :double :min 0.0 :default 20.0 :comment "VecReflection attacker position lookup radius for FX."}
    {:skill-id :vec-reflection :id :targeting.affected-entity-difficulty :path "targeting.affected-entity-difficulty" :section-suffix "targeting" :type :string-list :default ["minecraft:arrow:1.0" "minecraft:potion:1.4" "minecraft:snowball:0.1"] :comment "VecReflection projectile difficulty entries as registry-id:difficulty."}
    {:skill-id :vec-reflection :id :targeting.excluded-entity-ids :path "targeting.excluded-entity-ids" :section-suffix "targeting" :type :string-list :default ["minecraft:item" "minecraft:xp_bottle" "minecraft:experience_bottle"] :comment "VecReflection registry ids excluded from projectile handling."}
    {:skill-id :vec-reflection :id :cost.reflect-entity.cp :path "cost.reflect-entity.cp" :section-suffix "cost.reflect-entity" :type :double-list :min 0.0 :list-count 2 :default [300.0 160.0] :comment "VecReflection CP cost per reflected projectile difficulty."}
    {:skill-id :vec-reflection :id :combat.damage-multiplier :path "combat.damage-multiplier" :section-suffix "combat" :type :double-list :min 0.0 :list-count 2 :default [0.6 1.2] :comment "VecReflection incoming damage reflection multiplier."}
    {:skill-id :vec-reflection :id :combat.min-reflected-damage :path "combat.min-reflected-damage" :section-suffix "combat" :type :double :min 0.0 :default 1.0 :comment "VecReflection minimum reflected damage required to perform."}
    {:skill-id :vec-reflection :id :cost.damage.cp :path "cost.damage.cp" :section-suffix "cost.damage" :type :double-list :min 0.0 :list-count 2 :default [20.0 15.0] :comment "VecReflection CP consumed per incoming damage point."}
    {:skill-id :vec-reflection :id :progression.exp-reflect-entity-scale :path "progression.exp-reflect-entity-scale" :section-suffix "progression" :type :double :min 0.0 :default 0.0008 :comment "VecReflection exp gained per reflected projectile difficulty."}
    {:skill-id :vec-reflection :id :progression.exp-damage-scale :path "progression.exp-damage-scale" :section-suffix "progression" :type :double :min 0.0 :default 0.0004 :comment "VecReflection exp gained per reflected damage."}])

(def skill-tunable-definitions
  (vec (concat skill-tunable-definitions vecmanip-skill-tunable-definitions)))

(def field-definitions-by-id
  (into {} (map (juxt :id identity) field-definitions)))

(def skill-tunable-definitions-by-skill
  (group-by :skill-id skill-tunable-definitions))

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
               [skill-id (into {} (map (juxt :id identity) definitions))])
             skill-tunable-definitions-by-skill)))

(defn config-key
  [skill-id field-id]
  (keyword (str (name skill-id) "." (name field-id))))

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
  (vec (for [skill-def (get skills-by-category category-id)
             field-def (field-definitions-for-skill (:id skill-def))]
         (descriptor-for skill-def field-def))))

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
                         (map (juxt :key :default)
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

(defn- field-default
  [skill-id field-id]
  (let [skill-def (get skill-definitions-by-id skill-id)
        field-def (or (get field-definitions-by-id field-id)
                      (get-in skill-tunable-definitions-by-skill-field [skill-id field-id]))]
    (skill-field-default skill-def field-def)))

(defn- field-definition
  [skill-id field-id]
  (or (get field-definitions-by-id field-id)
      (get-in skill-tunable-definitions-by-skill-field [skill-id field-id])))

(defn raw-value
  [skill-id field-id]
  (let [domain (skill-domain skill-id)
        k (config-key skill-id field-id)]
    (if domain
      (get (config-reg/get-config-values domain) k (field-default skill-id field-id))
      (field-default skill-id field-id))))

(defn- finite-double
  [value default]
  (try
    (let [d (cond
              (number? value) (double value)
              (string? value) (Double/parseDouble value)
              :else (double default))]
      (if (or (Double/isNaN d) (Double/isInfinite d))
        (double default)
        d))
    (catch Exception _
      (double default))))

(defn- non-negative-double
  [skill-id field-id]
  (let [default (double (field-default skill-id field-id))
        value (finite-double (raw-value skill-id field-id) default)]
    (if (neg? value) default value)))

(defn- positive-double
  [skill-id field-id]
  (let [default (double (field-default skill-id field-id))
        value (finite-double (raw-value skill-id field-id) default)]
    (if (pos? value) value default)))

(defn- int-in-range
  [skill-id field-id]
  (let [{lower-bound :min upper-bound :max} (field-definition skill-id field-id)
        default (int (field-default skill-id field-id))
        value (int (Math/round (finite-double (raw-value skill-id field-id) default)))]
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
        value (finite-double (raw-value skill-id field-id) default)]
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
        value (int (Math/round (finite-double (raw-value skill-id field-id) default)))]
    (if (within-bounds? field-def value)
      value
      default)))

(defn- list-like?
  [value]
  (and (not (string? value))
       (seqable? value)))

(defn tunable-double-list
  "Read a fixed-length list of bounded doubles for a skill action tunable.

  Length mismatches fall back to the descriptor default. Individual invalid
  entries fall back to their corresponding default entry."
  [skill-id field-id]
  (let [{:keys [list-count] :as field-def} (field-definition skill-id field-id)
        fallback (vec (field-default skill-id field-id))
        raw (raw-value skill-id field-id)]
    (if (and (list-like? raw)
             (or (nil? list-count) (= (int list-count) (count raw))))
      (mapv (fn [value default]
              (let [d (finite-double value default)]
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
            (if (and (list-like? raw)
             (or (nil? list-count) (= (int list-count) (count raw))))
          (mapv (fn [value default]
              (let [i (int (Math/round (finite-double value default)))]
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
            (if (list-like? raw)
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
  (int (Math/round (double (lerp-double skill-id field-id exp)))))

(defn probability
  [skill-id field-id]
  (max 0.0 (min 1.0 (tunable-double skill-id field-id))))

(defn- boolean-value
  [skill-id field-id]
  (let [value (raw-value skill-id field-id)]
    (cond
      (instance? Boolean value) value
      (string? value) (Boolean/parseBoolean value)
      :else (boolean (field-default skill-id field-id)))))

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
    (fn [evt]
      (* (double scale) (double (or (value evt) 0.0))))

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

(defn validate-config!
  []
  (let [errors (atom [])]
    (doseq [skill-id all-skill-ids]
      (when-not (<= 1 (skill-level skill-id) 5)
        (swap! errors conj (str (name skill-id) ".general.level must be between 1 and 5")))
      (doseq [field-id [:damage-scale :cp-consume-speed :overload-consume-speed
                        :cooldown-scale :cost-cp-scale :cost-overload-scale]
              :let [value (finite-double (raw-value skill-id field-id) -1.0)]]
        (when (neg? value)
          (swap! errors conj (str (name skill-id) "." (name field-id) " must be non-negative"))))
      (let [exp-speed (finite-double (raw-value skill-id :exp-incr-speed) 0.0)]
        (when-not (pos? exp-speed)
          (swap! errors conj (str (name skill-id) ".progression.exp-incr-speed must be positive")))))
    (when (seq @errors)
      (throw (ex-info "Invalid ability skill configuration" {:errors @errors})))
    nil))
