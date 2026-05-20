(ns cn.li.ac.content.ability.teleporter.threatening-teleport
  "ThreateningTeleport skill - teleport behind target and deal damage.

  Pattern: :release-cast (aim + charge)
  Min hold: 5 ticks  |  Max hold: 60 ticks
  Range: lerp(20, 40, exp)
  Damage: lerp(5, 14, exp) * charge multiplier (1.0 → 2.0)
  Teleport offset: 1.5 blocks behind entity
  CP cost: lerp(150, 100, exp)
  Overload cost: lerp(60, 40, exp)
  Cooldown: lerp(35, 20, exp) ticks
  Exp: +0.003 per successful cast

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private threatening-teleport-skill-id :threatening-teleport)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- charge-ratio [hold-ticks]
  (let [min-hold (helper/cfg-int threatening-teleport-skill-id :charge.min-ticks)
        max-hold (helper/cfg-int threatening-teleport-skill-id :charge.max-ticks)]
    (-> (- (long hold-ticks) min-hold)
      (max 0)
      (/ (double (- max-hold min-hold)))
      (min 1.0))))

(defn- position-behind-entity
  "Return a map {:x :y :z} 1.5 blocks behind entity facing player."
  [entity-pos player-pos]
  (let [dx (- (double (:x player-pos)) (double (:x entity-pos)))
        dz (- (double (:z player-pos)) (double (:z entity-pos)))
        len (max 1.0e-6 (Math/sqrt (+ (* dx dx) (* dz dz))))
        nx (/ dx len) nz (/ dz len)]
    {:x (- (double (:x entity-pos)) (* (helper/cfg-double threatening-teleport-skill-id
                                :movement.behind-offset) nx))
     :y (double (:y entity-pos))
     :z (- (double (:z entity-pos)) (* (helper/cfg-double threatening-teleport-skill-id
                                :movement.behind-offset) nz))}))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn threatening-tp-down!
  [{:keys [ctx-id cost-ok?]}]
  (when cost-ok?
    (ctx/update-context! ctx-id assoc :skill-state {:target-uuid nil :ticks 0})))

(defn threatening-tp-tick!
  [{:keys [player-id ctx-id hold-ticks]}]
    (let [exp   (helper/skill-exp player-id threatening-teleport-skill-id)
      range (helper/cfg-lerp threatening-teleport-skill-id :targeting.range exp)
        hit   (helper/raycast-entity player-id range)]
    (ctx/update-context! ctx-id assoc :skill-state
                         {:target-uuid (when hit (:entity-uuid hit))
                          :target-x    (when hit (:entity-x hit))
                          :target-y    (when hit (:entity-y hit))
                          :target-z    (when hit (:entity-z hit))
                          :ticks hold-ticks
                          :has-target? (some? hit)})))

(defn threatening-tp-up!
  [{:keys [player-id ctx-id hold-ticks]}]
  (try
        (let [exp      (helper/skill-exp player-id threatening-teleport-skill-id)
          charge   (charge-ratio (long hold-ticks))
          damage   (* (helper/cfg-lerp threatening-teleport-skill-id :combat.damage exp)
              (+ 1.0 (* charge (helper/cfg-double threatening-teleport-skill-id
                          :combat.charge-bonus-multiplier))))
          ctx-data (ctx/get-context ctx-id)
          t-uuid   (get-in ctx-data [:skill-state :target-uuid])]
      (when t-uuid
        (let [world-id (geom/world-id-of player-id)
              player-pos (helper/player-position player-id)
              entity-pos (let [sx (get-in ctx-data [:skill-state :target-x])
                               sy (get-in ctx-data [:skill-state :target-y])
                               sz (get-in ctx-data [:skill-state :target-z])]
                           (when (and sx sy sz) {:x sx :y sy :z sz}))]
          (when (and player-pos entity-pos)
            (let [behind (position-behind-entity entity-pos player-pos)]
              (when (helper/teleport-to! player-id world-id
                                         (:x behind) (:y behind) (:z behind))
                (helper/deal-magic-damage! player-id world-id t-uuid damage)
                (skill-effects/add-skill-exp! player-id threatening-teleport-skill-id
                                              (helper/cfg-double threatening-teleport-skill-id
                                                                 :progression.exp-success))
                (let [cd (helper/cfg-lerp-int threatening-teleport-skill-id :cooldown.ticks exp)]
                  (skill-effects/set-main-cooldown! player-id threatening-teleport-skill-id cd))
                (ctx/ctx-send-to-client! ctx-id :threatening-tp/fx-perform
                                         {:x (:x behind) :y (:y behind) :z (:z behind)})))))))
    (catch Exception e
      (log/warn "ThreateningTeleport up! failed:" (ex-message e)))))

(defn threatening-tp-abort!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id dissoc :skill-state))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! threatening-teleport
  :id             :threatening-teleport
  :category-id    :teleporter
  :name-key       "ability.skill.teleporter.threatening_teleport"
  :description-key "ability.skill.teleporter.threatening_teleport.desc"
  :icon           "textures/abilities/teleporter/skills/threatening_teleport.png"
  :ui-position    [120 120]
  :level          1
  :controllable?  true
  :ctrl-id        :threatening-teleport
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :release-cast
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (helper/cfg-lerp threatening-teleport-skill-id
                                                       :cost.down.cp
                                                       (helper/skill-exp player-id threatening-teleport-skill-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (helper/cfg-lerp threatening-teleport-skill-id
                                                       :cost.down.overload
                                                       (helper/skill-exp player-id threatening-teleport-skill-id)))}}
  :cooldown       {:mode :manual}
  :actions        {:down!  threatening-tp-down!
                   :tick!  threatening-tp-tick!
                   :up!    threatening-tp-up!
                   :abort! threatening-tp-abort!}
  :fx             {:start {:topic :threatening-tp/fx-start :payload (fn [_] {})}
                   :end   {:topic :threatening-tp/fx-end   :payload (fn [_] {})}}
  :prerequisites  [])
