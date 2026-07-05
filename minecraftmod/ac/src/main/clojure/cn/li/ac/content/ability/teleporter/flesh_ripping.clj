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
  (:require [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def-skill-config-ops :flesh-ripping)
(def ^:private flesh-ripping-skill-id :flesh-ripping)

(defn- build-trace
  [player-id exp]
  (let [range (cfg-lerp :targeting.range exp)
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
  (ctx-skill/replace-skill-state! ctx-id {:hold-ticks 0
                                 :trace nil}))

(defn flesh-ripping-tick!
  [{:keys [player-id ctx-id hold-ticks]}]
  (let [exp (skill-exp player-id)
        trace (build-trace player-id exp)]
    (ctx-skill/replace-skill-state! ctx-id {:hold-ticks (long hold-ticks)
                     :trace trace})
    (when trace
      (fx/send! ctx-id {:topic :flesh-ripping/fx-update :mode :update} nil
                {:target-x (:target-x trace)
                 :target-y (:target-y trace)
                 :target-z (:target-z trace)
                 :hit? (:hit? trace)
                 :target-uuid (:target-uuid trace)}))))

(defn flesh-ripping-up!
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (let [exp (skill-exp player-id)
          damage (cfg-lerp :combat.damage exp)
          trace (or (get-in (ctx-skill/get-context ctx-id) [:skill-state :trace])
                    (build-trace player-id exp))]
      (if (and cost-ok? trace)
        (let [world-id (:world-id trace)
              e-uuid (:target-uuid trace)
              damage-result (helper/deal-magic-damage! player-id world-id e-uuid damage)]
          (when (helper/crit-applied? damage-result)
            (fx/send! ctx-id {:topic :teleporter/fx-crit-hit} nil
                      {:x (:target-x trace)
                       :y (:target-y trace)
                       :z (:target-z trace)
                       :crit-level (:crit-level damage-result)
                       :crit-rate (:crit-rate damage-result)
                       :message-key (:message-key damage-result)
                       :message-args (:message-args damage-result)
                       :target-uuid e-uuid
                       :skill-id flesh-ripping-skill-id}))
          (when (and (< (rand) (cfg-probability :effect.nausea-chance))
                     (potion-effects/available?))
            (potion-effects/apply-potion-effect!*
              e-uuid :nausea
              (cfg-int :effect.nausea-duration-ticks)
              (cfg-int :effect.nausea-amplifier)))
          (skill-effects/add-skill-exp! player-id flesh-ripping-skill-id
                                        (cfg-double :progression.exp-hit))
          (let [cd (cfg-lerp-int :cooldown.ticks exp)]
            (skill-effects/set-main-cooldown! player-id flesh-ripping-skill-id cd))
          (fx/send! ctx-id {:topic :flesh-ripping/fx-perform :mode :perform} nil
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
  (ctx-skill/clear-skill-state! ctx-id))

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
  :ui-position    [130 12]
  :level          3
  :controllable?  true
  :ctrl-id        :flesh-ripping
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :release-cast
  :cost           {:up {:cp       (fn [{:keys [player-id]}]
                                    (cfg-lerp :cost.up.cp
                                                     (skill-exp player-id)))
                      :overload (fn [{:keys [player-id]}]
                                  (cfg-lerp :cost.up.overload
                                                   (skill-exp player-id)))}}
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

