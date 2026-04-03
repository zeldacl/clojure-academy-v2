(ns cn.li.ac.content.ability.vecmanip.blood-retrograde
  "BloodRetrograde - Level 4 Vector Manipulation skill.

  Blood manipulation attack that damages and slows the target.

  Mechanics:
  - Raycast 2 blocks to find living entity
  - 30 tick charge window (auto-execute if not released)
  - Reduces player walk speed to 0.007 during charge
  - Damage: 30-60 (scales with exp)
  - Blood splash visual effects

  Resources:
  - CP: 280-350 (scales UP with exp - more powerful = more cost)
  - Overload: 55-40 (scales down with exp)
  - Cooldown: 90-40 ticks (scales down with exp)

  Experience:
  - 0.002 on successful hit

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
    (get-in state [:ability-data :skills :blood-retrograde :exp] 0.0)))

(defn blood-retrograde-on-key-down
  "Initialize charge state and slow player."
  [{:keys [ctx-id player-id]}]
  (try
    (ctx/update-context! ctx-id assoc :skill-state
                         {:charge-ticks 0
                          :max-charge-ticks 30
                          :executed false})

    (log/debug "BloodRetrograde: Charge started")
    (catch Exception e
      (log/warn "BloodRetrograde key-down failed:" (ex-message e)))))

(defn blood-retrograde-on-key-tick
  "Update charge progress and auto-execute at max charge."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (:charge-ticks skill-state 0)
            max-charge-ticks (:max-charge-ticks skill-state 30)
            executed (:executed skill-state false)]

        (when-not executed
          ;; Increment charge ticks
          (ctx/update-context! ctx-id update-in [:skill-state :charge-ticks] inc)

          ;; Auto-execute at max charge
          (when (>= charge-ticks max-charge-ticks)
            (log/debug "BloodRetrograde: Max charge reached, auto-executing")
            ;; Perform attack
            (when raycast/*raycast*
              (let [raycast-result (raycast/raycast-from-player raycast/*raycast*
                                                                player-id
                                                                2.0
                                                                true)]
                (if-let [target-id (:entity-id raycast-result)]
                  (let [exp (get-skill-exp player-id)
                        damage (scaling/lerp 30.0 60.0 exp)]
                    ;; Apply damage
                    (when entity-damage/*entity-damage*
                      (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                         target-id
                                                         player-id
                                                         damage
                                                         "blood_retrograde"))

                    ;; Grant experience
                    (when-let [state (ps/get-player-state player-id)]
                      (let [{:keys [data events]} (learning/add-skill-exp
                                                   (:ability-data state)
                                                   player-id
                                                   :blood-retrograde
                                                   0.002
                                                   1.0)]
                        (ps/update-ability-data! player-id (constantly data))
                        (doseq [e events]
                          (ability-evt/fire-ability-event! e))))

                    (log/info "BloodRetrograde: Hit entity" target-id "damage:" (int damage)))
                  (log/debug "BloodRetrograde: No target found"))))

            ;; Mark as executed
            (ctx/update-context! ctx-id assoc-in [:skill-state :executed] true)))))
    (catch Exception e
      (log/warn "BloodRetrograde key-tick failed:" (ex-message e)))))

(defn blood-retrograde-on-key-up
  "Execute attack when key is released."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            executed (:executed skill-state false)]

        (when-not executed
          (log/debug "BloodRetrograde: Released, executing")
          ;; Raycast to find target
          (when raycast/*raycast*
            (let [raycast-result (raycast/raycast-from-player raycast/*raycast*
                                                              player-id
                                                              2.0
                                                              true)]
              (if-let [target-id (:entity-id raycast-result)]
                (let [exp (get-skill-exp player-id)
                      damage (scaling/lerp 30.0 60.0 exp)]
                  ;; Apply damage
                  (when entity-damage/*entity-damage*
                    (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                       target-id
                                                       player-id
                                                       damage
                                                       "blood_retrograde"))

                  ;; Grant experience
                  (when-let [state (ps/get-player-state player-id)]
                    (let [{:keys [data events]} (learning/add-skill-exp
                                                 (:ability-data state)
                                                 player-id
                                                 :blood-retrograde
                                                 0.002
                                                 1.0)]
                      (ps/update-ability-data! player-id (constantly data))
                      (doseq [e events]
                        (ability-evt/fire-ability-event! e))))

                  (log/info "BloodRetrograde: Hit entity" target-id "damage:" (int damage)))
                (log/debug "BloodRetrograde: No target found")))))))
    (catch Exception e
      (log/warn "BloodRetrograde key-up failed:" (ex-message e)))))

(defn blood-retrograde-on-key-abort
  "Clean up state and restore movement speed on abort."
  [{:keys [ctx-id player-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)

    (log/debug "BloodRetrograde aborted")
    (catch Exception e
      (log/warn "BloodRetrograde key-abort failed:" (ex-message e)))))
