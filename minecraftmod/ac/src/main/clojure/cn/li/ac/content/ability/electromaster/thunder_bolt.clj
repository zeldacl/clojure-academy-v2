(ns cn.li.ac.content.ability.electromaster.thunder-bolt
  "ThunderBolt skill - instant targeted lightning strike with AOE damage.

  Pattern: :instant
  Cost: CP lerp(280,420), overload lerp(50,27) by exp
  Cooldown: lerp(120,50) ticks by exp
  Exp: +0.005 effective / +0.003 ineffective"
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]))

(def ^:private thunder-bolt-skill-id :thunder-bolt)

(defn- cfg-double [field-id]
  (skill-config/tunable-double thunder-bolt-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int thunder-bolt-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double thunder-bolt-skill-id field-id exp))

(defn- evt-lerp [field-id]
  (fn [{:keys [exp]}]
    (cfg-lerp field-id (double (or exp 0.0)))))

(defn- fallback-end-point [eye look range]
  (if look
    (geom/v+ eye (geom/v* look range))
    {:x (:x eye) :y (:y eye) :z (+ (:z eye) range)}))

(defn- block-impact-point [hit]
  {:x (double (or (:hit-x hit) (:x hit) 0.0))
   :y (double (or (:hit-y hit) (:y hit) 0.0))
   :z (double (or (:hit-z hit) (:z hit) 0.0))})

(defn- entity-impact-point [hit]
  {:x (double (or (:x hit) (:hit-x hit) 0.0))
   :y (+ (double (or (:y hit) (:hit-y hit) 0.0))
         (double (or (:eye-height hit) 0.0)))
   :z (double (or (:z hit) (:hit-z hit) 0.0))})

(defn- hit-kind [hit]
  (let [kind (:hit-type hit)]
    (cond
      (= kind :entity) :entity
      (= kind :block) :block
      :else :miss)))

(defn- resolve-attack-data [player-id range]
  (let [world-id (geom/world-id-of player-id)
        eye (geom/eye-pos player-id)
        look (when (raycast/available?)
               (raycast/get-player-look-vector* player-id))
        hit (when (and (raycast/available?) look)
              (raycast/raycast-combined*
                                        world-id
                                        (:x eye) (:y eye) (:z eye)
                                        (double (or (:x look) 0.0))
                                        (double (or (:y look) 0.0))
                                        (double (or (:z look) 1.0))
                                        (double range)))
        kind (hit-kind hit)
        target-uuid (when (= kind :entity)
                      (or (:uuid hit) (:entity-uuid hit) (:entity-id hit)))
        impact (case kind
                 :entity (entity-impact-point hit)
                 :block (block-impact-point hit)
                 (fallback-end-point eye look range))]
    {:world-id world-id
     :eye eye
     :look look
     :hit-kind kind
     :target-uuid target-uuid
     :impact impact}))

(defn- damage-entity! [world-id target-uuid damage]
  (when (and (entity-damage/available?) target-uuid (> (double damage) 0.0))
    (entity-damage/apply-direct-damage!*
                                        world-id
                                        target-uuid
                                        (double damage)
                                        :lightning)
    true))

(defn- aoe-victims [world-id center radius excluded]
  (if-not (world-effects/available?)
    []
    (->> (world-effects/find-entities-in-radius*
                                                 world-id
                                                 (double (:x center))
                                                 (double (:y center))
                                                 (double (:z center))
                                                 (double radius))
         (remove (fn [{:keys [uuid]}] (contains? excluded uuid)))
         vec)))

(defn- apply-aoe-damage! [world-id center radius amount victims]
  "Flat AOE damage matching original ThunderBolt: all targets in radius
  receive the full aoeDamage (no distance falloff)."
  (reduce (fn [hit-count {:keys [uuid]}]
            (if (damage-entity! world-id uuid (double amount))
              (inc hit-count)
              hit-count))
          0
          victims))

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

(defn thunder-bolt-perform! [{:keys [player-id ctx-id exp]}]
  (let [exp* (double (or exp 0.0))
        range (cfg-double :targeting.range)
        direct-damage (cfg-lerp :combat.direct-damage exp*)
        aoe-radius (cfg-double :combat.aoe-radius)
        aoe-damage (cfg-lerp :combat.aoe-damage exp*)
        cooldown-ticks (skill-config/lerp-int thunder-bolt-skill-id :cooldown.ticks exp*)
        {:keys [world-id eye hit-kind target-uuid impact]} (resolve-attack-data player-id range)
        excluded (cond-> #{player-id}
                   target-uuid (conj target-uuid))
        direct-hit? (and (= hit-kind :entity)
                         (damage-entity! world-id target-uuid direct-damage))
        victims (if (= hit-kind :miss)
                  []
                  (aoe-victims world-id impact aoe-radius excluded))
        aoe-hit-count (if (= hit-kind :miss)
                        0
                        (apply-aoe-damage! world-id impact aoe-radius aoe-damage victims))
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
  :level       4  ;; matching original Skill("thunder_bolt", 4)
  :controllable? false
  :ctrl-id     :thunder-bolt
  :pattern     :instant
  :cooldown    {:mode :manual}
  :cost        {:down {:cp       (evt-lerp :cost.down.cp)
                       :overload (evt-lerp :cost.down.overload)}}
  :cooldown-ticks (fn [{:keys [exp]}]
                    (skill-config/lerp-int thunder-bolt-skill-id
                                           :cooldown.ticks
                                           (double (or exp 0.0))))
  :actions     {:perform! thunder-bolt-perform!}
  :prerequisites [{:skill-id :arc-gen         :min-exp 0.0}
                  {:skill-id :current-charging :min-exp 0.7}])

