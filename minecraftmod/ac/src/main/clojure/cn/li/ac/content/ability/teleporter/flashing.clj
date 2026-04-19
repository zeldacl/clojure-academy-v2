(ns cn.li.ac.content.ability.teleporter.flashing
  "Flashing skill - rapid auto-teleport forward on each tick (toggle).

  Pattern: :toggle
  Blink distance per tick: lerp(0.5, 1.5, exp) blocks
  Blink interval: every lerp(6, 3, exp) ticks
  CP per blink: lerp(30, 18, exp)
  Overload per blink: lerp(8, 4, exp)
  No cooldown while active; 20-tick cooldown on deactivate.
  Exp: +0.001 per blink

  No WASD sub-key system (simplified from original's 4-direction blink).
  Always blinks in player look direction.

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (helper/skill-exp player-id :flashing))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn flashing-activate!
  [{:keys [ctx-id cost-ok?]}]
  (when cost-ok?
    (ctx/update-context! ctx-id assoc :skill-state {:tick-counter 0 :active? true})))

(defn flashing-tick!
  [{:keys [player-id ctx-id]}]
  (try
    (let [ctx-data    (ctx/get-context ctx-id)
          tick-count  (long (or (get-in ctx-data [:skill-state :tick-counter]) 0))
          exp         (skill-exp player-id)
          interval    (int (bal/lerp 6.0 3.0 exp))
          blink-dist  (bal/lerp 0.5 1.5 exp)
          cp-cost     (bal/lerp 30.0 18.0 exp)
          ol-cost     (bal/lerp 8.0 4.0 exp)
          new-tick    (inc tick-count)]
      (ctx/update-context! ctx-id assoc-in [:skill-state :tick-counter] new-tick)
      ;; Blink on interval
      (when (zero? (mod new-tick interval))
        (let [look-vec  (helper/player-look-vec player-id)
              player-pos (helper/player-position player-id)]
          (when (and look-vec player-pos)
            ;; Check CP available
            (let [state (ps/get-player-state player-id)
                  cur-cp (double (or (get-in state [:resource-data :cur-cp]) 0.0))]
              (when (>= cur-cp cp-cost)
                (let [world-id (geom/world-id-of player-id)
                      ;; Ignore Y component for horizontal blink
                      dx (double (:x look-vec))
                      dz (double (:z look-vec))
                      horiz-len (max 1.0e-6 (Math/sqrt (+ (* dx dx) (* dz dz))))
                      nx (/ dx horiz-len)
                      nz (/ dz horiz-len)
                      dest-x (+ (double (:x player-pos)) (* blink-dist nx))
                      dest-y (double (:y player-pos))
                      dest-z (+ (double (:z player-pos)) (* blink-dist nz))]
                  (when (helper/teleport-to! player-id world-id dest-x dest-y dest-z)
                    ;; Consume resources manually after blink
                    (skill-effects/perform-resource! player-id ol-cost cp-cost)
                    (skill-effects/add-skill-exp! player-id :flashing 0.001)
                    (ctx/ctx-send-to-client! ctx-id :flashing/fx-blink
                                             {:from-x (double (:x player-pos))
                                              :from-y dest-y
                                              :from-z (double (:z player-pos))}))))))))
      ;; Auto-deactivate if CP runs out
      (let [state (ps/get-player-state player-id)
            cur-cp (double (or (get-in state [:resource-data :cur-cp]) 0.0))]
        (when (<= cur-cp 0.0)
          (ctx/terminate-context! ctx-id))))
    (catch Exception e
      (log/warn "Flashing tick! failed:" (ex-message e)))))

(defn flashing-deactivate!
  [{:keys [player-id ctx-id]}]
  (skill-effects/set-main-cooldown! player-id :flashing 20))

(defn flashing-abort!
  [{:keys [player-id ctx-id]}]
  (skill-effects/set-main-cooldown! player-id :flashing 20))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! flashing
  :id             :flashing
  :category-id    :teleporter
  :name-key       "ability.skill.teleporter.flashing"
  :description-key "ability.skill.teleporter.flashing.desc"
  :icon           "textures/abilities/teleporter/skills/flashing.png"
  :ui-position    [120 160]
  :level          4
  :controllable?  true
  :ctrl-id        :flashing
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :toggle
  :cost           {:down {:overload (fn [{:keys [player-id]}]
                                     (bal/lerp 20.0 10.0 (skill-exp player-id)))}}
  :cooldown       {:mode :manual}
  :actions        {:activate!   flashing-activate!
                   :tick!       flashing-tick!
                   :deactivate! flashing-deactivate!
                   :abort!      flashing-abort!}
  :fx             {:start {:topic :flashing/fx-start :payload (fn [_] {})}
                   :end   {:topic :flashing/fx-end   :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :penetrate-teleport :min-exp 0.8}
                   {:skill-id :shift-teleport      :min-exp 0.8}])
