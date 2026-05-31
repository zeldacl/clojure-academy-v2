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
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.platform.potion-effects :as potion]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def ^:private flesh-ripping-skill-id :flesh-ripping)

(defn- build-trace
  [player-id exp]
  (let [range (helper/cfg-lerp flesh-ripping-skill-id :targeting.range exp)
        hit (helper/raycast-entity player-id range)]
    (when hit
      {:world-id (geom/world-id-of player-id)
       :hit? true
       :target-uuid (:entity-uuid hit)
       :target-x (:entity-x hit)
       :target-y (:entity-y hit)
       :target-z (:entity-z hit)})))

(defn flesh-ripping-down!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id assoc :skill-state {:hold-ticks 0
                                                  :trace nil}))

(defn flesh-ripping-tick!
  [{:keys [player-id ctx-id hold-ticks]}]
  (let [exp (helper/skill-exp player-id flesh-ripping-skill-id)
        trace (build-trace player-id exp)]
    (ctx/update-context! ctx-id assoc :skill-state {:hold-ticks (long hold-ticks)
                                                    :trace trace})
    (when trace
      (ctx/ctx-send-to-client! ctx-id :flesh-ripping/fx-update
                               {:target-x (:target-x trace)
                                :target-y (:target-y trace)
                                :target-z (:target-z trace)
                                :hit? (:hit? trace)
                                :target-uuid (:target-uuid trace)}))))

(defn flesh-ripping-up!
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (let [exp (helper/skill-exp player-id flesh-ripping-skill-id)
          damage (helper/cfg-lerp flesh-ripping-skill-id :combat.damage exp)
          trace (or (get-in (ctx/get-context ctx-id) [:skill-state :trace])
                    (build-trace player-id exp))]
      (if (and cost-ok? trace)
        (let [world-id (:world-id trace)
              e-uuid (:target-uuid trace)
              damage-result (helper/deal-magic-damage! player-id world-id e-uuid damage)]
          (when (helper/crit-applied? damage-result)
            (ctx/ctx-send-to-client! ctx-id :teleporter/fx-crit-hit
                                     {:x (:target-x trace)
                                      :y (:target-y trace)
                                      :z (:target-z trace)
                                      :crit-level (:crit-level damage-result)
                                      :crit-rate (:crit-rate damage-result)
                                      :message-key (:message-key damage-result)
                                      :message-args (:message-args damage-result)
                                      :target-uuid e-uuid
                                      :skill-id flesh-ripping-skill-id}))
          (when (and (< (rand) (helper/cfg-probability flesh-ripping-skill-id
                                                        :effect.nausea-chance))
                     potion/*potion-effects*)
            (potion/apply-potion-effect!
              potion/*potion-effects*
              e-uuid :nausea
              (helper/cfg-int flesh-ripping-skill-id :effect.nausea-duration-ticks)
              (helper/cfg-int flesh-ripping-skill-id :effect.nausea-amplifier)))
          (skill-effects/add-skill-exp! player-id flesh-ripping-skill-id
                                        (helper/cfg-double flesh-ripping-skill-id
                                                           :progression.exp-hit))
          (let [cd (helper/cfg-lerp-int flesh-ripping-skill-id :cooldown.ticks exp)]
            (skill-effects/set-main-cooldown! player-id flesh-ripping-skill-id cd))
          (ctx/ctx-send-to-client! ctx-id :flesh-ripping/fx-perform
                                   {:target-x (:target-x trace)
                                    :target-y (:target-y trace)
                                    :target-z (:target-z trace)
                                    :hit? true
                                    :target-uuid e-uuid}))
        (log/debug "FleshRipping: no entity in range or cost failed")))
    (catch Exception e
      (log/warn "FleshRipping up! failed:" (ex-message e)))))

(defn flesh-ripping-abort!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id dissoc :skill-state))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(declare flesh-ripping-skill)

(defskill flesh-ripping-skill
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
  :cost           {:up {:cp       (fn [{:keys [player-id]}]
                                    (helper/cfg-lerp flesh-ripping-skill-id
                                                     :cost.up.cp
                                                     (helper/skill-exp player-id flesh-ripping-skill-id)))
                      :overload (fn [{:keys [player-id]}]
                                  (helper/cfg-lerp flesh-ripping-skill-id
                                                   :cost.up.overload
                                                   (helper/skill-exp player-id flesh-ripping-skill-id)))}}
  :cooldown       {:mode :manual}
  :actions        {:down!  flesh-ripping-down!
                   :tick!  flesh-ripping-tick!
                   :up!    flesh-ripping-up!
                   :abort! flesh-ripping-abort!}
  :fx             {:start {:topic :flesh-ripping/fx-start :payload (fn [_] {})}
                   :update {:topic :flesh-ripping/fx-update :payload (fn [_] {})}
                   :end   {:topic :flesh-ripping/fx-end   :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :mark-teleport :min-exp 0.5}
                   {:skill-id :penetrate-teleport :min-exp 0.5}])

