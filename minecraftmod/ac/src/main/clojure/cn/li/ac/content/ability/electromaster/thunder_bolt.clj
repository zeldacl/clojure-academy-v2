(ns cn.li.ac.content.ability.electromaster.thunder-bolt
  "ThunderBolt skill - instant targeted lightning strike with AOE damage.

  Pattern: :instant
  Cost: CP lerp(280,420), overload lerp(50,27) by exp
  Cooldown: lerp(120,50) ticks by exp
  Exp: +0.005 effective / +0.003 ineffective"
  (:require [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.util.attack :as attack]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]))

(def-skill-config-ops :thunder-bolt)
(def ^:private thunder-bolt-skill-id :thunder-bolt)

(defn- cost-lerp [field-id]
  (fn [_player-id _skill-id exp]
    (cfg-lerp field-id (double (or exp 0.0)))))

(defn- try-apply-slowness! [target-uuid exp]
  (let [exp-threshold (cfg-double :effect.slowness-exp-threshold)
        chance (double (skill-config/probability thunder-bolt-skill-id :effect.slowness-chance))]
    (when (and (potion-effects/available?)
               target-uuid
               (> exp exp-threshold)
               (< (rand) chance))
      (potion-effects/apply-potion-effect!*
                                           target-uuid
                                           :slowness
                                           (cfg-int :effect.slowness-duration-ticks)
                                           (cfg-int :effect.slowness-amplifier))
      true)))

(defn thunder-bolt-perform!
  [ctx-id player-id _skill-id exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (let [exp* (double (or exp 0.0))
        range (cfg-double :targeting.range)
        direct-damage (cfg-lerp :combat.direct-damage exp*)
        aoe-radius (cfg-double :combat.aoe-radius)
        aoe-damage (cfg-lerp :combat.aoe-damage exp*)
        cooldown-ticks (skill-config/lerp-int thunder-bolt-skill-id :cooldown.ticks exp*)
        {:keys [world-id eye hit-kind target-uuid impact]} (attack/resolve-attack-data player-id range)
        excluded (cond-> #{player-id}
                   target-uuid (conj target-uuid))
        direct-hit? (and (= hit-kind :entity)
                         (attack/damage-entity! world-id target-uuid direct-damage :lightning))
        victims (if (= hit-kind :miss)
                  []
                  (attack/aoe-victims world-id impact aoe-radius excluded))
        aoe-hit-count (if (= hit-kind :miss)
                        0
                        (attack/apply-flat-aoe-damage! world-id victims aoe-damage :lightning))
        aoe-points (mapv (fn [{:keys [x y z eye-height]}]
                           {:x (double x)
                            :y (+ (double y) (double (or eye-height 0.0)))
                            :z (double z)})
                         victims)
        effective? (or direct-hit? (pos? aoe-hit-count))]
    (when (and (world-effects/available?) (not= hit-kind :miss))
      (world-effects/spawn-lightning!*
                                      world-id
                                      (double (:x impact))
                                      (double (:y impact))
                                      (double (:z impact))))
    (when direct-hit?
      (try-apply-slowness! target-uuid exp*))
    (fx/send! ctx-id {:topic :thunder-bolt/fx-perform} nil {:start eye
                              :end impact
                              :aoe-points aoe-points
                              :source-player-id player-id
                              :world-id world-id
                              :hit-kind hit-kind
                              :performed? true})
    (skill-effects/add-skill-exp! player-id
                                  thunder-bolt-skill-id
                                  (if effective?
                                    (cfg-double :progression.exp-effective)
                                    (cfg-double :progression.exp-ineffective)))
    (skill-effects/set-main-cooldown! player-id :thunder-bolt cooldown-ticks)
    nil))

(defskill thunder-bolt
  :id          :thunder-bolt
  :category-id :electromaster
  :name-key    "ability.skill.electromaster.thunder_bolt"
  :description-key "ability.skill.electromaster.thunder_bolt.desc"
  :icon        "textures/abilities/electromaster/skills/thunder_bolt.png"
  :ui-position [86 67]
  :ctrl-id     :thunder-bolt
  :pattern     :instant
  :cooldown    {:mode :manual}
  :cost        {:down {:cp       (cost-lerp :cost.down.cp)
                       :overload (cost-lerp :cost.down.overload)}}
  :cooldown-ticks (fn [{:keys [exp]}]
                    (skill-config/lerp-int thunder-bolt-skill-id
                                           :cooldown.ticks
                                           (double (or exp 0.0))))
  :actions     {:perform! thunder-bolt-perform!}
  :prerequisites [{:skill-id :arc-gen         :min-exp 0.0}
                  {:skill-id :current-charging :min-exp 0.7}])

