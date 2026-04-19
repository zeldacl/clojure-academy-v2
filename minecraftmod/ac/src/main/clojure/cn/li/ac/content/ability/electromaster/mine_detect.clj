(ns cn.li.ac.content.ability.electromaster.mine-detect
  "MineDetect skill - one-shot ore scanner with blindness effect.

  Pattern: :instant (high CP cost scan)
  Cost: CP lerp(600, 400, exp), overload lerp(80, 50, exp)
  Scan range: lerp(15, 30, exp) blocks
  Effect: blindness 100 ticks; sends ore positions to client for highlight
  Cooldown: lerp(200, 100, exp) ticks
  Exp: +0.003 per cast

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id)
                  [:ability-data :skills :mine-detect :exp]
                  0.0)))

(defn- scan-range [exp]
  (bal/lerp 15.0 30.0 exp))

(defn- ore-block? [block-id]
  (when block-id
    (or (str/includes? block-id "_ore")
        (= block-id "minecraft:ancient_debris"))))

(defn- ore-tier
  "Return 0=common, 1=valuable, 2=rare."
  [block-id]
  (cond
    (or (str/includes? block-id "diamond")
        (str/includes? block-id "emerald")
        (str/includes? block-id "ancient_debris")) 2
    (or (str/includes? block-id "gold")
        (str/includes? block-id "lapis")
        (str/includes? block-id "redstone")) 1
    :else 0))

;; ---------------------------------------------------------------------------
;; Action
;; ---------------------------------------------------------------------------

(defn mine-detect-perform!
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp       (skill-exp player-id)
          pos-state (get-in (ps/get-player-state player-id) [:position])
          world-id  (or (:world-id pos-state) "minecraft:overworld")
          x         (double (or (:x pos-state) 0.0))
          y         (double (or (:y pos-state) 64.0))
          z         (double (or (:z pos-state) 0.0))
          range     (scan-range exp)]
      ;; Apply blindness
      (when potion-effects/*potion-effects*
        (potion-effects/apply-potion-effect!
          potion-effects/*potion-effects*
          player-id :blindness 100 0))
      ;; Scan for ores and send to client
      (let [ores (when world-effects/*world-effects*
                   (world-effects/find-blocks-in-radius
                     world-effects/*world-effects*
                     world-id x y z range
                     ore-block?))]
        (ctx/ctx-send-to-client!
          ctx-id
          :mine-detect/fx-perform
          {:ores     (mapv (fn [block]
                             {:x    (int (:x block))
                              :y    (int (:y block))
                              :z    (int (:z block))
                              :tier (int (ore-tier (:block-id block)))})
                           (or ores []))
           :range    (double range)
           :advanced? (and (> exp 0.5)
                           (>= (get-in (ps/get-player-state player-id)
                                       [:ability-data :level]
                                       1)
                               4))})
        (skill-effects/add-skill-exp! player-id :mine-detect 0.003)
        (log/debug "MineDetect: found" (count ores) "ores in range" range)))
    (catch Exception e
      (log/warn "MineDetect perform! failed:" (ex-message e)))))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! mine-detect
  :id             :mine-detect
  :category-id    :electromaster
  :name-key       "ability.skill.electromaster.mine_detect"
  :description-key "ability.skill.electromaster.mine_detect.desc"
  :icon           "textures/abilities/electromaster/skills/mine_detect.png"
  :ui-position    [130 110]
  :level          2
  :controllable?  false
  :ctrl-id        :mine-detect
  :pattern        :instant
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (bal/lerp 600.0 400.0 (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (bal/lerp 80.0 50.0 (skill-exp player-id)))}}
  :cooldown-ticks (fn [{:keys [player-id]}]
                    (int (bal/lerp 200.0 100.0 (skill-exp player-id))))
  :actions        {:perform! mine-detect-perform!}
  :prerequisites  [{:skill-id :mag-manip :min-exp 1.0}])
