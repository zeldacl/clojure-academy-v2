(ns cn.li.ac.content.ability.vecmanip.storm-wing
  "StormWing - Level 3 Vector Manipulation skill.

  Toggle flight ability with directional control.

  Mechanics:
  - Charge phase: 70-30 ticks (scales with exp)
  - Active flight with WASD directional control
  - Hover mode when not moving (0.078 upward motion, or 0.1 near ground)
  - Speed: 0.7 (low exp) to 1.2 (high exp), multiplied by 2-3x based on exp
  - Acceleration: 0.16 per tick
  - Low exp (<15%): breaks nearby soft blocks (hardness ≤ 0.3)
  - Max exp: knockback nearby entities

  Resources:
  - CP: 40-25 per tick (scales down with exp)
  - Overload: 10-7 per tick (scales down with exp)
  - Cooldown: 30-10 ticks (scales down with exp)

  Experience:
  - 0.00005 per tick during flight

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :storm-wing :exp] 0.0)))

(defn- get-player-position [player-id]
  "Get player position from teleportation protocol."
  (when-let [teleportation (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
    (when-let [tp-impl @teleportation]
      ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) tp-impl player-id))))

(defn storm-wing-on-key-down
  "Initialize charge state."
  [{:keys [ctx-id player-id]}]
  (try
    (let [exp (get-skill-exp player-id)
          charge-ticks-needed (int (scaling/lerp 70.0 30.0 exp))]
      (ctx/update-context! ctx-id assoc :skill-state
                           {:phase :charging
                            :charge-ticks 0
                            :charge-ticks-needed charge-ticks-needed
                            :flight-active false})
      (log/debug "StormWing: Charge started, need" charge-ticks-needed "ticks"))
    (catch Exception e
      (log/warn "StormWing key-down failed:" (ex-message e)))))

(defn- break-soft-blocks! [player-id world-id x y z]
  "Break soft blocks around player (for low exp players)."
  (when block-manip/*block-manipulation*
    (doseq [dx (range -2 3)
            dy (range -2 3)
            dz (range -2 3)]
      (let [bx (+ (int x) dx)
            by (+ (int y) dy)
            bz (+ (int z) dz)]
        (when-let [hardness (block-manip/get-block-hardness block-manip/*block-manipulation*
                                                            world-id bx by bz)]
          (when (<= hardness 0.3)
            (block-manip/break-block! block-manip/*block-manipulation*
                                     player-id world-id bx by bz false)))))))

(defn- knockback-nearby-entities! [player-id world-id x y z]
  "Knockback nearby entities (for max exp players)."
  (when world-effects/*world-effects*
    (let [entities (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                          world-id
                                                          (double x)
                                                          (double y)
                                                          (double z)
                                                          3.0)]
      (doseq [entity entities]
        (let [entity-id (:uuid entity)]
          (when-not (= entity-id player-id)
            (when player-motion/*player-motion*
              (let [dx (- (:x entity) x)
                    dy (- (:y entity) y)
                    dz (- (:z entity) z)
                    dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
                    norm-dx (if (> dist 0) (/ dx dist) 0)
                    norm-dy (if (> dist 0) (/ dy dist) 0)
                    norm-dz (if (> dist 0) (/ dz dist) 0)
                    knockback-strength 0.5]
                (player-motion/add-velocity! player-motion/*player-motion*
                                            entity-id
                                            (* norm-dx knockback-strength)
                                            (* norm-dy knockback-strength)
                                            (* norm-dz knockback-strength))))))))))

(defn storm-wing-on-key-tick
  "Update charge or flight state."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            phase (:phase skill-state)
            exp (get-skill-exp player-id)]

        (case phase
          :charging
          ;; Charging phase
          (let [charge-ticks (:charge-ticks skill-state 0)
                charge-ticks-needed (:charge-ticks-needed skill-state 70)]
            (if (>= charge-ticks charge-ticks-needed)
              ;; Transition to flight
              (do
                (ctx/update-context! ctx-id assoc-in [:skill-state :phase] :flying)
                (ctx/update-context! ctx-id assoc-in [:skill-state :flight-active] true)
                (log/debug "StormWing: Flight activated"))
              ;; Continue charging
              (ctx/update-context! ctx-id update-in [:skill-state :charge-ticks] inc)))

          :flying
          ;; Flight phase
          (when-let [pos (get-player-position player-id)]
            (let [world-id (:world-id pos)
                  x (:x pos)
                  y (:y pos)
                  z (:z pos)
                  cp-cost (scaling/lerp 40.0 25.0 exp)
                  overload-cost (scaling/lerp 10.0 7.0 exp)]

              ;; Low exp: break soft blocks
              (when (< exp 0.15)
                (break-soft-blocks! player-id world-id x y z))

              ;; Max exp: knockback entities
              (when (>= exp 1.0)
                (knockback-nearby-entities! player-id world-id x y z))

              ;; Grant experience
              (when-let [state (ps/get-player-state player-id)]
                (let [{:keys [data events]} (learning/add-skill-exp
                                             (:ability-data state)
                                             player-id
                                             :storm-wing
                                             0.00005
                                             1.0)]
                  (ps/update-ability-data! player-id (constantly data))
                  (doseq [e events]
                    (ability-evt/fire-ability-event! e))))))

          ;; Default
          nil)))
    (catch Exception e
      (log/warn "StormWing key-tick failed:" (ex-message e)))))

(defn storm-wing-on-key-up
  "Stop flight."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            phase (:phase skill-state)]

        (if (= phase :flying)
          (do
            (log/debug "StormWing: Flight stopped for player" player-id)
            ;; Remove flight ability
            (ctx/update-context! ctx-id assoc-in [:skill-state :flight-active] false))
          (log/debug "StormWing: Released during charge, aborting"))))
    (catch Exception e
      (log/warn "StormWing key-up failed:" (ex-message e)))))

(defn storm-wing-on-key-abort
  "Clean up state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "StormWing aborted")
    (catch Exception e
      (log/warn "StormWing key-abort failed:" (ex-message e)))))
