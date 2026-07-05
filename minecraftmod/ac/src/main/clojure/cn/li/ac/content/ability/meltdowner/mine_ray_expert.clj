(ns cn.li.ac.content.ability.meltdowner.mine-ray-expert
  "MineRayExpert - all-tier mining beam, extended range.

  Pattern: :hold-channel
  Range: lerp(16, 22, exp)
  Break speed: lerp(0.4, 0.8, exp) per tick
  Tick cost: CP lerp(18, 12, exp)
  Down cost: overload lerp(80, 55, exp)
  Cooldown: 5 ticks
  Exp: +0.001 per block broken

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.content.ability.meltdowner.mine-rays-base :as base]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------


(def-skill-config-ops :mine-ray-expert)
(def ^:private mine-ray-expert-skill-id :mine-ray-expert)

(defn- make-cfg [player-id]
  (let [exp (skill-exp player-id)]
    {:range       (cfg-lerp :targeting.range exp)
     :break-speed (cfg-lerp :mining.break-speed exp)
     :skill-id    mine-ray-expert-skill-id
     :exp-block   (cfg-double :progression.exp-block)
     :lucky?      false}))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn mine-ray-expert-down!  [evt] (base/mining-ray-down!  mine-ray-expert-skill-id evt))
(defn mine-ray-expert-tick!  [evt] (base/mining-ray-tick!  (make-cfg (:player-id evt)) evt))
(defn mine-ray-expert-up!    [evt] (base/mining-ray-up!    {} evt))
(defn mine-ray-expert-abort! [evt] (base/mining-ray-abort! {} evt))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill mine-ray-expert
  :id             :mine-ray-expert
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.mine_ray_expert"
  :description-key "ability.skill.meltdowner.mine_ray_expert.desc"
  :icon           "textures/abilities/meltdowner/skills/mine_ray_expert.png"
  :ui-position    [172 70]
  :level          4
  :controllable?  true
  :ctrl-id        :mine-ray-expert
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :hold-channel
  :cost           {:down {:overload (fn [{:keys [player-id]}]
                (cfg-lerp :cost.down.overload (skill-exp player-id)))}
                   :tick {:cp (fn [{:keys [player-id]}]
              (cfg-lerp :cost.tick.cp (skill-exp player-id)))} }
    :cooldown-ticks (fn [{:keys [exp]}] (skill-config/lerp-int mine-ray-expert-skill-id :cooldown.ticks (double (or exp 0.0))))  ;; matching original lerp(50,25,exp)
  :actions        {:down!  mine-ray-expert-down!
                   :tick!  mine-ray-expert-tick!
                   :up!    mine-ray-expert-up!
                   :abort! mine-ray-expert-abort!}
  :fx             {:start  {:topic :mine-ray/fx-start  :payload (fn [_] {:variant :expert})}
                   :end    {:topic :mine-ray/fx-end    :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :mine-ray-basic :min-exp 0.8}])
