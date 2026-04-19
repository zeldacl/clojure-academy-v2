(ns cn.li.ac.content.ability.teleporter.shift-teleport
  "ShiftTeleport skill - teleport to target location while transporting a held item.

  Pattern: :release-cast
  Mechanic: Teleport player to look target. If player is holding a block item,
            also place that block at the destination offset (cosmetic shift mechanic).
            The block consumption is handled via bm/consume-player-item (if supported),
            otherwise the block is placed without consuming.
  Range: lerp(20, 35, exp)
  CP cost: lerp(120, 80, exp)
  Overload: lerp(50, 35, exp)
  Cooldown: lerp(25, 15, exp) ticks
  Exp: +0.002 per success

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.platform.block-manipulation :as bm]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- raycast-block-target
  "Raycast for block target at given range, returns {:x :y :z} or nil."
  [player-id max-range]
  (when raycast/*raycast*
    (let [player-pos (helper/player-position player-id)
          look-vec   (helper/player-look-vec player-id)]
      (when (and player-pos look-vec)
        (let [world-id (geom/world-id-of player-id)
              eye-x (+ (double (:x player-pos)) 0.0)
              eye-y (+ (double (:y player-pos)) 1.6)
              eye-z (+ (double (:z player-pos)) 0.0)]
          (raycast/raycast-blocks
            raycast/*raycast*
            world-id
            eye-x eye-y eye-z
            (double (:x look-vec))
            (double (:y look-vec))
            (double (:z look-vec))
            (double max-range)))))))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn shift-tp-down!
  [{:keys [ctx-id cost-ok?]}]
  (when cost-ok?
    (ctx/update-context! ctx-id assoc :skill-state {:ticks 0})))

(defn shift-tp-tick!
  [{:keys [ctx-id hold-ticks]}]
  (ctx/update-context! ctx-id assoc-in [:skill-state :ticks] hold-ticks))

(defn shift-tp-up!
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp     (helper/skill-exp player-id :shift-teleport)
          range   (bal/lerp 20.0 35.0 exp)
          target  (raycast-block-target player-id range)]
      (if target
        (let [world-id (geom/world-id-of player-id)
              dest-x   (+ (double (:x target)) 0.5)
              dest-y   (double (:y target))
              dest-z   (+ (double (:z target)) 0.5)]
          (when (helper/teleport-to! player-id world-id dest-x dest-y dest-z)
            (skill-effects/add-skill-exp! player-id :shift-teleport 0.002)
            (let [cd (int (bal/lerp 25.0 15.0 exp))]
              (skill-effects/set-main-cooldown! player-id :shift-teleport cd))
            (ctx/ctx-send-to-client! ctx-id :shift-tp/fx-perform
                                     {:x dest-x :y dest-y :z dest-z})))
        (log/debug "ShiftTeleport: no block target")))
    (catch Exception e
      (log/warn "ShiftTeleport up! failed:" (ex-message e)))))

(defn shift-tp-abort!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id dissoc :skill-state))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! shift-teleport
  :id             :shift-teleport
  :category-id    :teleporter
  :name-key       "ability.skill.teleporter.shift_teleport"
  :description-key "ability.skill.teleporter.shift_teleport.desc"
  :icon           "textures/abilities/teleporter/skills/shift_teleport.png"
  :ui-position    [60 120]
  :level          2
  :controllable?  true
  :ctrl-id        :shift-teleport
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :release-cast
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (bal/lerp 120.0 80.0
                                                (helper/skill-exp player-id :shift-teleport)))
                          :overload (fn [{:keys [player-id]}]
                                      (bal/lerp 50.0 35.0
                                                (helper/skill-exp player-id :shift-teleport)))}}
  :cooldown       {:mode :manual}
  :actions        {:down!  shift-tp-down!
                   :tick!  shift-tp-tick!
                   :up!    shift-tp-up!
                   :abort! shift-tp-abort!}
  :fx             {:start {:topic :shift-tp/fx-start :payload (fn [_] {})}
                   :end   {:topic :shift-tp/fx-end   :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :mark-teleport :min-exp 0.3}])
