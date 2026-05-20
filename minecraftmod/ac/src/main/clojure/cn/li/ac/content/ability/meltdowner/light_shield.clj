(ns cn.li.ac.content.ability.meltdowner.light-shield
  "LightShield skill - toggle energy barrier that absorbs damage and pushes enemies.

  Pattern: :toggle
  Activation cost: CP lerp(200, 160, exp), overload lerp(100, 70, exp)
  Tick cost: CP lerp(12, 8, exp) per tick
  Damage reduction: lerp(0.5, 0.8, exp) of incoming damage
  Touch damage: lerp(3, 8, exp) to entities in front 60° cone within 3 blocks
  On deactivate: slowness II for 60 ticks
  Cooldown: lerp(100, 60, exp) ticks (manual)
  Exp: +0.0004 per damage absorbed

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.damage.handler :as damage-handler]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private light-shield-skill-id :light-shield)

(defn- cfg-double [field-id]
  (skill-config/tunable-double light-shield-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int light-shield-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double light-shield-skill-id field-id exp))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int light-shield-skill-id field-id exp))

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id light-shield-skill-id))

(defn- get-player-position [player-id]
  (when-let [teleportation (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
    (when-let [tp-impl @teleportation]
      ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) tp-impl player-id))))

(defn- in-front-cone?
  "Check if entity is within 60° cone in front of player."
  [player-pos player-look entity-pos]
  (when (and player-pos player-look entity-pos)
    (let [dx (- (double (:x entity-pos)) (double (:x player-pos)))
          dy (- (double (:y entity-pos)) (double (:y player-pos)))
          dz (- (double (:z entity-pos)) (double (:z player-pos)))
          len (max 1.0e-6 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))
          nx (/ dx len) ny (/ dy len) nz (/ dz len)
          dot (+ (* nx (double (:x player-look)))
                 (* ny (double (:y player-look)))
                 (* nz (double (:z player-look))))]
      (> dot (cfg-double :combat.front-cone-dot)))))  ; cos(60°) = 0.5

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn light-shield-activate!
  [{:keys [ctx-id player-id]}]
  (ctx/update-context! ctx-id assoc-in [:skill-state :shield-ticks] 0)
  (ctx/ctx-send-to-client! ctx-id :light-shield/fx-start {})
  (log/info "LightShield: Activated"))

(defn light-shield-deactivate!
  [{:keys [player-id ctx-id]}]
  (when potion-effects/*potion-effects*
    (potion-effects/apply-potion-effect!
      potion-effects/*potion-effects*
      player-id :slowness
      (cfg-int :effect.deactivate-slowness-duration-ticks)
      (cfg-int :effect.slowness-amplifier)))
  (let [exp (skill-exp player-id)]
    (skill-effects/set-main-cooldown!
      player-id light-shield-skill-id
      (cfg-lerp-int :cooldown.ticks exp)))
  (ctx/ctx-send-to-client! ctx-id :light-shield/fx-end {})
  (log/info "LightShield: Deactivated"))

(defn light-shield-tick!
  [{:keys [player-id ctx-id cost-ok?]}]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (when (and cost-ok? (toggle/is-toggle-active? ctx-data :light-shield))
      (let [exp      (skill-exp player-id)
            pos      (get-player-position player-id)
            world-id (or (:world-id pos) (skill-effects/player-path player-id [:position :world-id]) "minecraft:overworld")]
        (ctx/update-context! ctx-id update-in [:skill-state :shield-ticks] (fnil inc 0))
        ;; Touch damage entities in front 60° cone
        (when (and pos world-effects/*world-effects*)
          (let [entities (world-effects/find-entities-in-radius
                           world-effects/*world-effects*
                           world-id (:x pos) (:y pos) (:z pos)
                           (cfg-double :combat.touch-radius))
                look-vec (when-let [raycast (resolve 'cn.li.mcmod.platform.raycast/*raycast*)]
                           (when-let [rc-impl @raycast]
                             ((resolve 'cn.li.mcmod.platform.raycast/get-player-look-vector)
                              rc-impl player-id)))]
            (doseq [entity entities]
              (when (and (:living? entity)
                         (not= (:uuid entity) player-id)
                         (in-front-cone? pos look-vec entity))
                (when entity-damage/*entity-damage*
                  (md-damage/mark-target! player-id (:uuid entity))
                  (entity-damage/apply-direct-damage!
                    entity-damage/*entity-damage*
                    world-id (:uuid entity)
                    (cfg-lerp :combat.touch-damage exp)
                    :magic))))))))))

(defn light-shield-abort!
  [{:keys [ctx-id player-id]}]
  (when potion-effects/*potion-effects*
    (potion-effects/apply-potion-effect!
      potion-effects/*potion-effects*
      player-id :slowness
      (cfg-int :effect.abort-slowness-duration-ticks)
      (cfg-int :effect.slowness-amplifier)))
  (toggle/remove-toggle! ctx-id :light-shield)
  (ctx/ctx-send-to-client! ctx-id :light-shield/fx-end {}))

;; ---------------------------------------------------------------------------
;; Damage reduction handler
;; ---------------------------------------------------------------------------

(defn- light-shield-reduce-damage
  [player-id _attacker-id damage _damage-source]
  (try
    (let [exp (skill-exp player-id)
        reduction (cfg-lerp :combat.damage-reduction exp)
          new-damage (* damage (- 1.0 reduction))]
      (skill-effects/add-skill-exp! player-id light-shield-skill-id
                    (* damage (cfg-double :progression.exp-absorbed-scale)))
      [(double new-damage) {:absorbed (- damage new-damage)}])
    (catch Exception e
      (log/warn "LightShield reduce-damage failed:" (ex-message e))
      [damage nil])))

;; Register damage handler at load time
(damage-handler/register-toggle-damage-handler!
  :light-shield-damage
  :light-shield
  light-shield-reduce-damage
  80)

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! light-shield
  :id             :light-shield
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.light_shield"
  :description-key "ability.skill.meltdowner.light_shield.desc"
  :icon           "textures/abilities/meltdowner/skills/light_shield.png"
  :ui-position    [155 140]
  :level          2
  :controllable?  true
  :ctrl-id        :light-shield
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 1
  :pattern        :toggle
  :cooldown       {:mode :manual}
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                (cfg-lerp :cost.down.cp (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
                (cfg-lerp :cost.down.overload (skill-exp player-id)))}
                   :tick {:cp (fn [{:keys [player-id]}]
              (cfg-lerp :cost.tick.cp (skill-exp player-id)))} }
  :actions        {:activate!   light-shield-activate!
                   :deactivate! light-shield-deactivate!
                   :tick!       light-shield-tick!
                   :abort!      light-shield-abort!}
  :fx             {:start {:topic :light-shield/fx-start :payload (fn [_] {})}
                   :end   {:topic :light-shield/fx-end   :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :electron-bomb :min-exp 1.0}])
