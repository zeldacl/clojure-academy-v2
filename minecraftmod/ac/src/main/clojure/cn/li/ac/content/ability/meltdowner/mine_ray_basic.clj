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
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.content.ability.meltdowner.mine-rays-base :as base]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id)
                  [:ability-data :skills :mine-ray-basic :exp]
                  0.0)))

(defn- make-cfg [player-id]
  {:range       (bal/lerp 8.0 12.0 (skill-exp player-id))
   :break-speed (bal/lerp 0.15 0.35 (skill-exp player-id))
   :skill-id    :mine-ray-basic
   :lucky?      false})

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn mine-ray-basic-down!  [evt] (base/mining-ray-down!  :mine-ray-basic evt))
(defn mine-ray-basic-tick!  [evt] (base/mining-ray-tick!  (make-cfg (:player-id evt)) evt))
(defn mine-ray-basic-up!    [evt] (base/mining-ray-up!    {} evt))
(defn mine-ray-basic-abort! [evt] (base/mining-ray-abort! {} evt))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! mine-ray-basic
  :id             :mine-ray-basic
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.mine_ray_basic"
  :description-key "ability.skill.meltdowner.mine_ray_basic.desc"
  :icon           "textures/abilities/meltdowner/skills/mine_ray_basic.png"
  :ui-position    [128 160]
  :level          3
  :controllable?  true
  :ctrl-id        :mine-ray-basic
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :hold-channel
  :cost           {:down {:overload (fn [{:keys [player-id]}]
                                      (bal/lerp 60.0 40.0 (skill-exp player-id)))}
                   :tick {:cp (fn [{:keys [player-id]}]
                                (bal/lerp 12.0 8.0 (skill-exp player-id)))}}
  :cooldown-ticks 5
  :actions        {:down!  mine-ray-basic-down!
                   :tick!  mine-ray-basic-tick!
                   :up!    mine-ray-basic-up!
                   :abort! mine-ray-basic-abort!}
  :fx             {:start  {:topic :mine-ray/fx-start  :payload (fn [_] {:variant :basic})}
                   :end    {:topic :mine-ray/fx-end    :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :meltdowner :min-exp 0.3}])
