(ns cn.li.ac.content.ability.vecmanip.vec-accel
  "VecAccel skill - dash/acceleration in look direction.

  Mechanics:
  - Charge up to 20 ticks for increased speed
  - Accelerates player in look direction (pitch - 10°)
  - Max velocity: 2.5 blocks/tick
  - Speed scales with charge time (sin curve: 0.4-1.0)
  - Dismounts riding entities
  - Resets fall damage
  - Requires ground at <50% experience, ignores at 50%+
  - CP: 120-80, Overload: 30-15, Cooldown: 80-50 ticks
  - Experience gain: 0.002 per use

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :vec-accel :exp] 0.0)))

(def ^:private MAX-VELOCITY 2.5)
(def ^:private MAX-CHARGE 20)

(defn- calculate-speed
  "Calculate speed based on charge time using sin curve."
  [charge-ticks]
  (let [prog (scaling/lerp 0.4 1.0 (min 1.0 (/ (double charge-ticks) MAX-CHARGE)))]
    (* (Math/sin prog) MAX-VELOCITY)))

(defn vec-accel-on-key-down
  "Initialize charge state."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id assoc :skill-state
                         {:charge-ticks 0
                          :can-perform false})
    (log/debug "VecAccel: Charge started")
    (catch Exception e
      (log/warn "VecAccel key-down failed:" (ex-message e)))))

(defn vec-accel-on-key-tick
  "Update charge progress and check if can perform."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (:charge-ticks skill-state 0)
            exp (get-skill-exp player-id)]

        ;; Increment charge ticks (max 20)
        (when (< charge-ticks MAX-CHARGE)
          (ctx/update-context! ctx-id update-in [:skill-state :charge-ticks] inc))

        ;; Check if can perform (ground check or 50%+ exp)
        (let [ignore-ground? (>= exp 0.5)
              on-ground? (when player-motion/*player-motion*
                          (player-motion/is-on-ground? player-motion/*player-motion* player-id))
              can-perform? (or ignore-ground? on-ground?)]

          (ctx/update-context! ctx-id assoc-in [:skill-state :can-perform] can-perform?))

        ;; Grant minimal experience during charge
        (when-let [state (ps/get-player-state player-id)]
          (let [{:keys [data events]} (learning/add-skill-exp
                                       (:ability-data state)
                                       player-id
                                       :vec-accel
                                       0.00001
                                       1.0)]
            (ps/update-ability-data! player-id (constantly data))
            (doseq [e events]
              (ability-evt/fire-ability-event! e))))))
    (catch Exception e
      (log/warn "VecAccel key-tick failed:" (ex-message e)))))

(defn vec-accel-on-key-up
  "Perform the acceleration."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (:charge-ticks skill-state 0)
            can-perform? (:can-perform skill-state false)
            exp (get-skill-exp player-id)]
        (if-not can-perform?
          (log/debug "VecAccel: Cannot perform (not on ground and exp <50%)")
          ;; Get player look direction
          (when (and raycast/*raycast*
                     (some? (raycast/get-player-look-vector raycast/*raycast* player-id)))
            (let [look-vec (raycast/get-player-look-vector raycast/*raycast* player-id)
                  ;; Convert to radians: -10° = -0.174533 rad
                  pitch-adjust -0.174533
                  look-x (:x look-vec)
                  look-y (:y look-vec)
                  look-z (:z look-vec)
                  horiz-len (Math/sqrt (+ (* look-x look-x) (* look-z look-z)))
                  current-pitch (Math/atan2 (- look-y) horiz-len)
                  new-pitch (+ current-pitch pitch-adjust)
                  cos-pitch (Math/cos new-pitch)
                  sin-pitch (Math/sin new-pitch)
                  horiz-x (/ look-x horiz-len)
                  horiz-z (/ look-z horiz-len)
                  new-x (* cos-pitch horiz-x)
                  new-y (- sin-pitch)
                  new-z (* cos-pitch horiz-z)
                  speed (calculate-speed charge-ticks)
                  vel-x (* new-x speed)
                  vel-y (* new-y speed)
                  vel-z (* new-z speed)]
              (when player-motion/*player-motion*
                (player-motion/set-velocity! player-motion/*player-motion*
                                             player-id
                                             vel-x vel-y vel-z)
                (player-motion/dismount-riding! player-motion/*player-motion* player-id))
              (when teleportation/*teleportation*
                (teleportation/reset-fall-damage! teleportation/*teleportation* player-id))
              (when-let [state (ps/get-player-state player-id)]
                (let [{:keys [data events]} (learning/add-skill-exp
                                             (:ability-data state)
                                             player-id
                                             :vec-accel
                                             0.002
                                             1.0)]
                  (ps/update-ability-data! player-id (constantly data))
                  (doseq [e events]
                    (ability-evt/fire-ability-event! e))))
              (log/info "VecAccel: Accelerated with speed" (format "%.2f" speed)
                        "charge:" charge-ticks))))))
    (catch Exception e
      (log/warn "VecAccel key-up failed:" (ex-message e)))))

(defn vec-accel-on-key-abort
  "Clean up state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "VecAccel aborted")
    (catch Exception e
      (log/warn "VecAccel key-abort failed:" (ex-message e)))))
