(ns cn.li.ac.content.ability.meltdowner.mine-ray-luck
  "MineRayLuck - fortune-enhanced mining beam.

  Pattern: :hold-channel
  Range: lerp(16, 22, exp)
  Break speed: lerp(0.5, 1.0, exp)
  Fortune effect: drops extra items via block manipulation fortune parameter
  Tick cost: CP lerp(22, 15, exp)
  Down cost: overload lerp(100, 70, exp)
  Cooldown: 5 ticks
  Exp: +0.002 per block broken

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.meltdowner.mine-rays-base :as base]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private mine-ray-luck-skill-id :mine-ray-luck)
(def ^:private mine-ray-luck-fortune-level 3)

(defn- cfg-int [field-id]
  (skill-config/tunable-int mine-ray-luck-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double mine-ray-luck-skill-id field-id exp))

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id mine-ray-luck-skill-id))

(defn- make-cfg [player-id]
  (let [exp (skill-exp player-id)]
    {:range         (cfg-lerp :targeting.range exp)
     :break-speed   (cfg-lerp :mining.break-speed exp)
     :skill-id      mine-ray-luck-skill-id
     :fortune-level mine-ray-luck-fortune-level
     :exp-block     (skill-config/tunable-double mine-ray-luck-skill-id :progression.exp-block)}))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn mine-ray-luck-down!  [evt] (base/mining-ray-down!  mine-ray-luck-skill-id evt))
(defn mine-ray-luck-tick!  [evt] (base/mining-ray-tick!  (make-cfg (:player-id evt)) evt))
(defn mine-ray-luck-up!    [evt] (base/mining-ray-up!    {} evt))
(defn mine-ray-luck-abort! [evt] (base/mining-ray-abort! {} evt))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill mine-ray-luck
  :id             :mine-ray-luck
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.mine_ray_luck"
  :description-key "ability.skill.meltdowner.mine_ray_luck.desc"
  :icon           "textures/abilities/meltdowner/skills/mine_ray_luck.png"
  :ui-position    [205 82]
  :level          5
  :controllable?  true
  :ctrl-id        :mine-ray-luck
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :hold-channel
  :cost           {:down {:overload (fn [{:keys [player-id]}]
                (cfg-lerp :cost.down.overload (skill-exp player-id)))}
                   :tick {:cp (fn [{:keys [player-id]}]
              (cfg-lerp :cost.tick.cp (skill-exp player-id)))} }
    :cooldown-ticks (fn [_] (cfg-int :cooldown.ticks))
  :actions        {:down!  mine-ray-luck-down!
                   :tick!  mine-ray-luck-tick!
                   :up!    mine-ray-luck-up!
                   :abort! mine-ray-luck-abort!}
  :fx             {:start  {:topic :mine-ray/fx-start  :payload (fn [_] {:variant :luck})}
                   :end    {:topic :mine-ray/fx-end    :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :mine-ray-expert :min-exp 1.0}])
