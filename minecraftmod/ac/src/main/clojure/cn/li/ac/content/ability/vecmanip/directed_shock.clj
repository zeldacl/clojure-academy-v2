(ns cn.li.ac.content.ability.vecmanip.directed-shock
  "DirectedShock skill - melee punch attack.

  Mechanics:
  - Raycast to find entity within 3 blocks
  - Damage: 7-15 (scales with experience)
  - Knockback at 25%+ experience (0.7 velocity)
  - Minimum charge: 6 ticks, optimal: <50 ticks, max tolerant: 200 ticks
  - Experience gain: 0.0035 on hit, 0.001 on miss
  - CP: 50-100, Overload: 18-12, Cooldown: 60-20 ticks

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :directed-shock :exp] 0.0)))

(defn directed-shock-on-key-down
  "Initialize charge state."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id assoc :skill-state
                         {:charge-ticks 0
                          :punched false})
    (log/debug "DirectedShock: Charge started")
    (catch Exception e
      (log/warn "DirectedShock key-down failed:" (ex-message e)))))

(defn directed-shock-on-key-tick
  "Update charge progress."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (:charge-ticks skill-state 0)]

        ;; Increment charge ticks
        (ctx/update-context! ctx-id update-in [:skill-state :charge-ticks] inc)

        ;; Auto-abort if held too long
        (when (>= charge-ticks 200)
          (log/debug "DirectedShock: Max tolerant ticks reached, aborting")
          (ctx/update-context! ctx-id dissoc :skill-state))

        ;; Grant minimal experience during charge
        (when-let [state (ps/get-player-state player-id)]
          (let [{:keys [data events]} (learning/add-skill-exp
                                       (:ability-data state)
                                       player-id
                                       :directed-shock
                                       0.00001
                                       1.0)]
            (ps/update-ability-data! player-id (constantly data))
            (doseq [e events]
              (ability-evt/fire-ability-event! e))))))
    (catch Exception e
      (log/warn "DirectedShock key-tick failed:" (ex-message e)))))

(defn directed-shock-on-key-up
  "Perform the punch attack."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (:charge-ticks skill-state 0)
            exp (get-skill-exp player-id)]

        ;; Check if charge is valid (6-50 ticks)
        (if (and (>= charge-ticks 6) (< charge-ticks 50))
          (do
            ;; Raycast to find target entity
            (when raycast/*raycast*
              (let [raycast-result (raycast/raycast-from-player raycast/*raycast*
                                                                player-id
                                                                3.0
                                                                true)]  ; living only

                (if-let [target-id (:entity-id raycast-result)]
                  ;; Hit entity
                  (let [damage (scaling/scale-damage 7.0 15.0 exp)]
                    ;; Apply damage
                    (when entity-damage/*entity-damage*
                      (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                         target-id
                                                         player-id
                                                         damage
                                                         "directed_shock"))

                    ;; Apply knockback at 25%+ experience
                    (when (and (>= exp 0.25) player-motion/*player-motion*)
                      (let [knockback-vel 0.7]
                        (player-motion/add-velocity! player-motion/*player-motion*
                                                    target-id
                                                    0.0
                                                    knockback-vel
                                                    0.0)))

                    ;; Grant experience
                    (when-let [state (ps/get-player-state player-id)]
                      (let [{:keys [data events]} (learning/add-skill-exp
                                                   (:ability-data state)
                                                   player-id
                                                   :directed-shock
                                                   0.0035
                                                   1.0)]
                        (ps/update-ability-data! player-id (constantly data))
                        (doseq [e events]
                          (ability-evt/fire-ability-event! e))))

                    (log/info "DirectedShock: Hit entity" target-id "damage:" (int damage)))

                  ;; Missed
                  (do
                    (when-let [state (ps/get-player-state player-id)]
                      (let [{:keys [data events]} (learning/add-skill-exp
                                                   (:ability-data state)
                                                   player-id
                                                   :directed-shock
                                                   0.001
                                                   1.0)]
                        (ps/update-ability-data! player-id (constantly data))
                        (doseq [e events]
                          (ability-evt/fire-ability-event! e))))
                    (log/debug "DirectedShock: Missed")))))

            ;; Mark as punched for animation
            (ctx/update-context! ctx-id assoc-in [:skill-state :punched] true))

          ;; Invalid charge time
          (log/debug "DirectedShock: Invalid charge time" charge-ticks))))
    (catch Exception e
      (log/warn "DirectedShock key-up failed:" (ex-message e)))))

(defn directed-shock-on-key-abort
  "Clean up state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "DirectedShock aborted")
    (catch Exception e
      (log/warn "DirectedShock key-abort failed:" (ex-message e)))))
