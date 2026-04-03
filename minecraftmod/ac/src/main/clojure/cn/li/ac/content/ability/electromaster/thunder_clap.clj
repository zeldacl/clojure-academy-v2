(ns cn.li.ac.content.ability.electromaster.thunder-clap
  "ThunderClap skill - channeled AOE lightning attack with charge mechanics.

  Mechanics:
  - Hold key to charge (40-60 ticks minimum)
  - Release to strike with lightning at target location
  - Charge multiplier affects damage and cooldown (0.8x-1.2x)
  - Damage: 36-72 base, scaled by experience and charge multiplier
  - Range: 15-30 blocks, scaled by experience
  - Walk speed decreases during charge
  - Cooldown: 120-200 ticks × charge multiplier

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.ac.ability.util.charge :as charge]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :thunder-clap :exp] 0.0)))

(defn thunder-clap-on-key-down
  "Initialize charge state when key pressed."
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (get-skill-exp player-id)
          ;; Charge timing scales with experience
          min-ticks (int (scaling/scale-duration 60 40 exp))
          max-ticks (int (scaling/scale-duration 100 80 exp))
          optimal-ticks (int (/ (+ min-ticks max-ticks) 2))

          charge-state (charge/init-charge-state min-ticks max-ticks optimal-ticks)]

      ;; Store charge state in context
      (ctx/update-context! ctx-id assoc :skill-state {:charge charge-state})

      (log/debug "ThunderClap charge started, min:" min-ticks "optimal:" optimal-ticks))
    (catch Exception e
      (log/warn "ThunderClap key-down failed:" (ex-message e)))))

(defn thunder-clap-on-key-tick
  "Update charge progress each tick."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            charge-state (:charge skill-state)

            ;; Update charge progress
            new-charge-state (charge/update-charge-progress charge-state)
            progress (charge/get-charge-progress-ratio new-charge-state)]

        ;; Update context with new charge state
        (ctx/update-context! ctx-id assoc-in [:skill-state :charge] new-charge-state)

        ;; Grant small experience during charge
        (when-let [state (ps/get-player-state player-id)]
          (let [{:keys [data events]} (learning/add-skill-exp
                                       (:ability-data state)
                                       player-id
                                       :thunder-clap
                                       0.0001
                                       1.0)]
            (ps/update-ability-data! player-id (constantly data))
            (doseq [e events]
              (ability-evt/fire-ability-event! e))))

        (when (zero? (mod (:charge-ticks new-charge-state) 20))
          (log/debug "ThunderClap charging:" (int (* progress 100)) "%"))))
    (catch Exception e
      (log/warn "ThunderClap key-tick failed:" (ex-message e)))))

(defn thunder-clap-on-key-up
  "Execute ThunderClap when key released."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            charge-state (:charge skill-state)
            exp (get-skill-exp player-id)]

        ;; Check if minimum charge reached
        (if-not (charge/is-charge-complete? charge-state)
          (log/debug "ThunderClap: Insufficient charge")

          (let [;; Calculate charge multiplier
                charge-mult (charge/get-charge-multiplier charge-state)

                ;; Scale parameters by experience and charge
                base-damage (scaling/scale-damage 36.0 72.0 exp)
                damage (* base-damage charge-mult)
                range (scaling/scale-range 15.0 30.0 exp)
                aoe-radius 8.0

                ;; Get player look vector and position
                look-vec (when raycast/*raycast*
                           (raycast/get-player-look-vector raycast/*raycast* player-id))
                player-state (ps/get-player-state player-id)
                player-pos (get player-state :position {:x 0.0 :y 64.0 :z 0.0})]

            (if-not look-vec
              (log/warn "ThunderClap: Could not get player look vector")

              (let [{:keys [x y z]} player-pos
                    {:keys [x dx y dy z dz]} look-vec

                    ;; Raycast to find target location
                    hit (when raycast/*raycast*
                          (raycast/raycast-combined raycast/*raycast*
                                                    "minecraft:overworld"
                                                    x (+ y 1.6) z
                                                    dx dy dz
                                                    range))]

                (if-not hit
                  (log/debug "ThunderClap: No target found")

                  (let [target-x (get hit :x 0.0)
                        target-y (get hit :y 64.0)
                        target-z (get hit :z 0.0)]

                    ;; Spawn lightning at target
                    (when world-effects/*world-effects*
                      (world-effects/spawn-lightning! world-effects/*world-effects*
                                                      "minecraft:overworld"
                                                      target-x target-y target-z))

                    ;; Apply AOE damage
                    (when entity-damage/*entity-damage*
                      (entity-damage/apply-aoe-damage! entity-damage/*entity-damage*
                                                       "minecraft:overworld"
                                                       target-x target-y target-z
                                                       aoe-radius
                                                       damage
                                                       :lightning
                                                       true))

                    ;; Grant experience based on charge quality
                    (when-let [state (ps/get-player-state player-id)]
                      (let [exp-gain (* 0.015 charge-mult)
                            {:keys [data events]} (learning/add-skill-exp
                                                   (:ability-data state)
                                                   player-id
                                                   :thunder-clap
                                                   exp-gain
                                                   1.0)]
                        (ps/update-ability-data! player-id (constantly data))
                        (doseq [e events]
                          (ability-evt/fire-ability-event! e))))

                    (log/debug "ThunderClap executed: damage" (int damage)
                               "mult" (format "%.2f" charge-mult))))))))))
    (catch Exception e
      (log/warn "ThunderClap key-up failed:" (ex-message e)))))

(defn thunder-clap-on-key-abort
  "Clean up charge state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "ThunderClap charge aborted")
    (catch Exception e
      (log/warn "ThunderClap key-abort failed:" (ex-message e)))))
