(ns cn.li.ac.content.ability.teleporter.threatening-teleport
  "ThreateningTeleport skill - execute throw/damage sequence at aim position.

  Pattern: :release-cast (aim while holding key, execute on key up)
  Range: lerp(8, 15, exp)
  Damage: lerp(3, 6, exp)
  CP cost (up-stage): lerp(35, 100, exp)
  Overload cost (up-stage): lerp(18, 10, exp)
  Cooldown: lerp(30, 15, exp) ticks
  Exp: 0.003 * (hit? 1.0 : 0.2)

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private threatening-teleport-skill-id :threatening-teleport)
(def ^:private default-eye-height 1.62)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- has-main-hand-item?
  [player]
  (and player
       (pos? (int (or (entity/player-get-main-hand-item-count player) 0)))))

(defn- consume-or-drop-main-hand-item!
  [player trace drop?]
  (if (nil? player)
    true
    (if drop?
      (entity/player-drop-main-hand-item-at! player
                                             1
                                             (double (:drop-x trace))
                                             (double (:drop-y trace))
                                             (double (:drop-z trace)))
      (entity/player-consume-main-hand-item! player 1))))

(defn- trace-result
  [player-id range]
  (let [player-pos (helper/player-position player-id)
        look-vec (helper/player-look-vec player-id)
        world-id (geom/world-id-of player-id)]
    (when (and player-pos look-vec raycast/*raycast*)
      (let [sx (double (:x player-pos))
            sy (+ (double (:y player-pos)) default-eye-height)
            sz (double (:z player-pos))
            dx (double (:x look-vec))
            dy (double (:y look-vec))
            dz (double (:z look-vec))
            hit (raycast/raycast-combined raycast/*raycast*
                                          world-id
                                          sx sy sz dx dy dz
                                          (double range))]
        (if hit
          (let [hit-x (double (or (:hit-x hit) (:x hit) sx))
                hit-y (double (or (:hit-y hit) (:y hit) sy))
                hit-z (double (or (:hit-z hit) (:z hit) sz))
                target-uuid (or (:entity-uuid hit) (:uuid hit))
                attacked? (= :entity (:hit-type hit))]
            {:world-id world-id
             :start-x sx :start-y sy :start-z sz
             :drop-x hit-x :drop-y hit-y :drop-z hit-z
             :attacked? attacked?
             :target-uuid target-uuid
             :distance (double (or (:distance hit)
                                   (Math/sqrt (+ (* (- hit-x sx) (- hit-x sx))
                                                 (* (- hit-y sy) (- hit-y sy))
                                                 (* (- hit-z sz) (- hit-z sz))))))})
          (let [mx (+ sx (* dx (double range)))
                my (+ sy (* dy (double range)))
                mz (+ sz (* dz (double range)))]
            {:world-id world-id
             :start-x sx :start-y sy :start-z sz
             :drop-x mx :drop-y my :drop-z mz
             :attacked? false
             :target-uuid nil
             :distance (double range)}))))))

(defn- exp-gain
  [attacked?]
  (* (helper/cfg-double threatening-teleport-skill-id :progression.exp-base)
     (if attacked?
       (helper/cfg-double threatening-teleport-skill-id :progression.exp-hit-factor)
       (helper/cfg-double threatening-teleport-skill-id :progression.exp-miss-factor))))

(defn- should-drop?
  [attacked?]
  (< (rand)
     (if attacked?
       (helper/cfg-probability threatening-teleport-skill-id :interaction.drop-prob.hit)
       (helper/cfg-probability threatening-teleport-skill-id :interaction.drop-prob.miss))))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn threatening-tp-down!
  [{:keys [ctx-id cost-ok?]}]
  (when cost-ok?
    (ctx/update-context! ctx-id assoc :skill-state {:hold-ticks 0 :trace nil})))

(defn threatening-tp-tick!
  [{:keys [player-id ctx-id hold-ticks]}]
  (let [exp (helper/skill-exp player-id threatening-teleport-skill-id)
        range (helper/cfg-lerp threatening-teleport-skill-id :targeting.range exp)
        trace (trace-result player-id range)]
    (ctx/update-context! ctx-id assoc :skill-state {:hold-ticks (long hold-ticks)
                                                    :trace trace})
    (when trace
      (ctx/ctx-send-to-client! ctx-id :threatening-tp/fx-update
                               {:start-x (:start-x trace)
                                :start-y (:start-y trace)
                                :start-z (:start-z trace)
                                :drop-x (:drop-x trace)
                                :drop-y (:drop-y trace)
                                :drop-z (:drop-z trace)
                                :attacked? (:attacked? trace)
                                :target-uuid (:target-uuid trace)}))))

(defn threatening-tp-up!
  [{:keys [player-id ctx-id player cost-ok?]}]
  (try
    (let [exp (helper/skill-exp player-id threatening-teleport-skill-id)
          damage (helper/cfg-lerp threatening-teleport-skill-id :combat.damage exp)
          ctx-data (ctx/get-context ctx-id)
          range (helper/cfg-lerp threatening-teleport-skill-id :targeting.range exp)
          trace (or (get-in ctx-data [:skill-state :trace])
                    (trace-result player-id range))]
      (when (and cost-ok? trace (has-main-hand-item? player))
        (let [world-id (:world-id trace)
              target-uuid (:target-uuid trace)
              attacked? (boolean (and (:attacked? trace) target-uuid))
              drop? (should-drop? attacked?)
              consumed? (consume-or-drop-main-hand-item! player trace drop?)]
          (when consumed?
            (when attacked?
              (helper/deal-magic-damage! player-id world-id target-uuid damage))
            (skill-effects/add-skill-exp! player-id threatening-teleport-skill-id (exp-gain attacked?))
            (let [cd (helper/cfg-lerp-int threatening-teleport-skill-id :cooldown.ticks exp)]
              (skill-effects/set-main-cooldown! player-id threatening-teleport-skill-id cd))
            (ach-dispatcher/trigger-custom-event! player-id "teleporter.threatening_teleport")
            (ctx/ctx-send-to-client! ctx-id :threatening-tp/fx-perform
                                     {:start-x (:start-x trace)
                                      :start-y (:start-y trace)
                                      :start-z (:start-z trace)
                                      :drop-x (:drop-x trace)
                                      :drop-y (:drop-y trace)
                                      :drop-z (:drop-z trace)
                                      :attacked? attacked?
                                      :dropped? drop?})))))
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
  :cost           {:up {:cp       (fn [{:keys [player-id]}]
                                    (helper/cfg-lerp threatening-teleport-skill-id
                                                     :cost.up.cp
                                                     (helper/skill-exp player-id threatening-teleport-skill-id)))
                        :overload (fn [{:keys [player-id]}]
                                    (helper/cfg-lerp threatening-teleport-skill-id
                                                     :cost.up.overload
                                                     (helper/skill-exp player-id threatening-teleport-skill-id)))
                        :creative (fn [{:keys [player]}]
                                    (boolean (and player (entity/player-creative? player))))}}
  :cooldown       {:mode :manual}
  :actions        {:down!  threatening-tp-down!
                   :tick!  threatening-tp-tick!
                   :up!    threatening-tp-up!
                   :abort! threatening-tp-abort!}
  :fx             {:start {:topic :threatening-tp/fx-start :payload (fn [_] {})}
                   :update {:topic :threatening-tp/fx-update :payload (fn [_] {})}
                   :end   {:topic :threatening-tp/fx-end   :payload (fn [_] {})}}
  :prerequisites  [])
