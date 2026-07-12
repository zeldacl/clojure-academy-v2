(ns cn.li.ac.content.ability.meltdowner.mine-ray-basic
  "MineRayBasic - iron-tier mining beam, short range.

  Pattern: :hold-channel
  Range: lerp(8, 12, exp)
  Break speed: lerp(0.15, 0.35, exp) per tick
  Tick cost: CP lerp(12, 8, exp)
  Down cost: overload lerp(60, 40, exp)
  Cooldown: 5 ticks
  Exp: +0.001 per block broken

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.meltdowner.mine-rays-base :as base]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------


(def-skill-config-ops :mine-ray-basic)
(def ^:private mine-ray-basic-skill-id :mine-ray-basic)

(defn- make-cfg [player-id]
  (let [exp (skill-exp player-id)]
    {:range       (cfg-lerp :targeting.range exp)
     :break-speed (cfg-lerp :mining.break-speed exp)
     :skill-id    mine-ray-basic-skill-id
     :exp-block   (cfg-double :progression.exp-block)
     :lucky?      false}))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn mine-ray-basic-down!  [& args] (apply base/mining-ray-down!  mine-ray-basic-skill-id args))
(defn mine-ray-basic-tick!  [& args] (apply base/mining-ray-tick!  (make-cfg (nth args 1)) args))
(defn mine-ray-basic-up!    [& args] (apply base/mining-ray-up!    {} args))
(defn mine-ray-basic-abort! [& args] (apply base/mining-ray-abort! {} args))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill mine-ray-basic
  :id             :mine-ray-basic
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.mine_ray_basic"
  :description-key "ability.skill.meltdowner.mine_ray_basic.desc"
  :icon           "textures/abilities/meltdowner/skills/mine_ray_basic.png"
  :ui-position    [140 70]
  :ctrl-id        :mine-ray-basic
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :hold-channel
  :cost           {:down {:overload (fn [{:keys [player-id]}]
                (cfg-lerp :cost.down.overload (skill-exp player-id)))}
                   :tick {:cp (fn [{:keys [player-id]}]
              (cfg-lerp :cost.tick.cp (skill-exp player-id)))} }
    :cooldown-ticks (fn [{:keys [exp]}] (skill-config/lerp-int mine-ray-basic-skill-id :cooldown.ticks (double (or exp 0.0))))  ;; matching original lerp(40,20,exp)
  :actions        {:down!  mine-ray-basic-down!
                   :tick!  mine-ray-basic-tick!
                   :up!    mine-ray-basic-up!
                   :abort! mine-ray-basic-abort!}
  :fx             {:start  {:topic :mine-ray/fx-start  :payload (fn [_] {:variant :basic})}
                   :end    {:topic :mine-ray/fx-end    :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :meltdowner :min-exp 0.3}])
