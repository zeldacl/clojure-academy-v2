(ns cn.li.ac.content.ability.teleporter.flesh-ripping
  "FleshRipping skill - grab and injure nearby enemies by partial teleportation.

  Pattern: :release-cast
  Range: lerp(15, 25, exp)
  Damage: lerp(6, 16, exp)
  Nausea chance: 30% (3 ticks per level)
  CP cost: lerp(180, 130, exp)
  Overload: lerp(70, 50, exp)
  Cooldown: lerp(30, 18, exp) ticks
  Exp: +0.003 per entity hit

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.platform.potion-effects :as potion]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn flesh-ripping-down!
  [{:keys [ctx-id cost-ok?]}]
  (when cost-ok?
    (ctx/update-context! ctx-id assoc :skill-state {:ticks 0})))

(defn flesh-ripping-tick!
  [{:keys [ctx-id hold-ticks]}]
  (ctx/update-context! ctx-id assoc-in [:skill-state :ticks] hold-ticks))

(defn flesh-ripping-up!
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp    (helper/skill-exp player-id :flesh-ripping)
          damage (bal/lerp 6.0 16.0 exp)
          range  (bal/lerp 15.0 25.0 exp)
          hit    (helper/raycast-entity player-id range)]
      (if hit
        (let [world-id (geom/world-id-of player-id)
              e-uuid   (:entity-uuid hit)]
          (helper/deal-magic-damage! player-id world-id e-uuid damage)
          ;; Apply nausea with 30% chance
          (when (and (< (rand) 0.30) potion/*potion-effects*)
            (potion/apply-potion-effect!
              potion/*potion-effects*
              e-uuid :nausea 60 0))
          (skill-effects/add-skill-exp! player-id :flesh-ripping 0.003)
          (let [cd (int (bal/lerp 30.0 18.0 exp))]
            (skill-effects/set-main-cooldown! player-id :flesh-ripping cd))
          (ctx/ctx-send-to-client! ctx-id :flesh-ripping/fx-perform
                                   {:target-x (:entity-x hit)
                                    :target-y (:entity-y hit)
                                    :target-z (:entity-z hit)}))
        (log/debug "FleshRipping: no entity in range")))
    (catch Exception e
      (log/warn "FleshRipping up! failed:" (ex-message e)))))

(defn flesh-ripping-abort!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id dissoc :skill-state))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! flesh-ripping
  :id             :flesh-ripping
  :category-id    :teleporter
  :name-key       "ability.skill.teleporter.flesh_ripping"
  :description-key "ability.skill.teleporter.flesh_ripping.desc"
  :icon           "textures/abilities/teleporter/skills/flesh_ripping.png"
  :ui-position    [180 120]
  :level          3
  :controllable?  true
  :ctrl-id        :flesh-ripping
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :release-cast
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (bal/lerp 180.0 130.0
                                                (helper/skill-exp player-id :flesh-ripping)))
                          :overload (fn [{:keys [player-id]}]
                                      (bal/lerp 70.0 50.0
                                                (helper/skill-exp player-id :flesh-ripping)))}}
  :cooldown       {:mode :manual}
  :actions        {:down!  flesh-ripping-down!
                   :tick!  flesh-ripping-tick!
                   :up!    flesh-ripping-up!
                   :abort! flesh-ripping-abort!}
  :fx             {:start {:topic :flesh-ripping/fx-start :payload (fn [_] {})}
                   :end   {:topic :flesh-ripping/fx-end   :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :mark-teleport :min-exp 0.5}
                   {:skill-id :penetrate-teleport :min-exp 0.5}])
