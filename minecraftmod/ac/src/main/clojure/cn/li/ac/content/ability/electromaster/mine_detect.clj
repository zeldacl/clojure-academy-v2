(ns cn.li.ac.content.ability.electromaster.mine-detect
  "MineDetect skill - one-shot ore detector with blindness effect.

  Pattern: :instant (single activation)
  Cost: CP lerp(1500, 1000, exp), overload lerp(200, 180, exp)
  Scan range: lerp(15, 30, exp) blocks
  Effect: blindness 100 ticks; sends activation payload to client
  Cooldown: lerp(900, 400, exp) ticks
  Exp: +0.008 per cast

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private mine-detect-skill-id :mine-detect)

(defn- cfg-double [field-id]
  (skill-config/tunable-double mine-detect-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int mine-detect-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double mine-detect-skill-id field-id exp))

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id mine-detect-skill-id))

(defn- scan-range [exp]
  (cfg-lerp :targeting.range exp))

(defn- advanced-mode?
  [player-id exp]
  (and (> (double exp) 0.5)
       (>= (long (skill-effects/player-path player-id [:ability-data :level] 1))
           4)))

;; ---------------------------------------------------------------------------
;; Action
;; ---------------------------------------------------------------------------

(defn mine-detect-perform!
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp       (skill-exp player-id)
          range     (scan-range exp)
          advanced? (advanced-mode? player-id exp)]
      (when (potion-effects/available?)
        (potion-effects/apply-potion-effect!*
          player-id :blindness
          (cfg-int :effect.blindness-duration-ticks)
          (cfg-int :effect.blindness-amplifier)))
      (fx/send! ctx-id {:topic :mine-detect/fx-perform :mode :perform} nil
                {:life-ticks 100
                 :rescan-interval 5
                 :range (double range)
                 :advanced? advanced?})
      (skill-effects/add-skill-exp! player-id mine-detect-skill-id
                                    (cfg-double :progression.exp-cast))
      (skill-effects/set-main-cooldown! player-id mine-detect-skill-id
                                         (skill-config/lerp-int mine-detect-skill-id
                                                                :cooldown.ticks
                                                                exp))
      (log/debug "MineDetect: activated with range" range "advanced?" advanced?))
    (catch Exception e
      (log/warn "MineDetect perform! failed:" (ex-message e)))))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill mine-detect
  :id             :mine-detect
  :category-id    :electromaster
  :name-key       "ability.skill.electromaster.mine_detect"
  :description-key "ability.skill.electromaster.mine_detect.desc"
  :icon           "textures/abilities/electromaster/skills/mine_detect.png"
  :ui-position    [130 110]
  :level          3
  :controllable?  false
  :ctrl-id        :mine-detect
  :pattern        :instant
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (cfg-lerp :cost.down.cp (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (cfg-lerp :cost.down.overload (skill-exp player-id)))}}
  :cooldown-ticks (fn [{:keys [player-id]}]
                    (skill-config/lerp-int mine-detect-skill-id
                                           :cooldown.ticks
                                           (skill-exp player-id)))
  :actions        {:perform! mine-detect-perform!}
  :prerequisites  [{:skill-id :mag-manip :min-exp 1.0}])
