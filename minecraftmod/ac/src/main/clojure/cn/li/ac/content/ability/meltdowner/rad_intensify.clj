(ns cn.li.ac.content.ability.meltdowner.rad-intensify
  "Meltdowner passive: radiation intensify.
  Custom exp is derived from max-cp ratio; damage rate lerps from 1.4 to 1.8."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.util.balance :as bal]))

(defn skill-exp
  [player-id]
  (let [max-cp (double (or (get-in (ps/get-player-state player-id) [:resource-data :max-cp]) 0.0))
        level5-cp (double (cfg/max-cp-for-level 5))]
    (bal/clamp01
      (if (pos? level5-cp)
        (/ max-cp level5-cp)
        0.0))))

(defn rate
  [player-id]
  (bal/lerp 1.4 1.8 (skill-exp player-id)))

(defskill! rad-intensify
  :id :rad-intensify
  :category-id :meltdowner
  :name-key "ability.skill.meltdowner.rad_intensify"
  :description-key "ability.skill.meltdowner.rad_intensify.desc"
  :icon "textures/abilities/meltdowner/skills/rad_intensify.png"
  :ui-position [35 75]
  :level 1
  :controllable? false
  :ctrl-id :rad-intensify
  :pattern :passive
  :prerequisites [{:skill-id :electron-bomb :min-exp 0.5}])
