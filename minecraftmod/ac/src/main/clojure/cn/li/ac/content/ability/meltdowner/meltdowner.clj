(ns cn.li.ac.content.ability.meltdowner.meltdowner
  "Meltdowner skill - charged beam attack with reflection mechanics.

  Mechanics:
  - Hold key 20-100 ticks (1-5 seconds) to charge
  - Release to fire beam
  - Energy: 10-15 per tick consumption + 200-170 initial overload (scales with exp)
  - Damage: Base 18-50 × charge rate modifier (0.8x-1.2x, peaks at 40 ticks)
  - Energy Output: 300-700 (scales with charge and experience)
  - Cooldown: ~300-100 ticks depending on charge and experience
  - Reflection: 50% reflected damage to secondary targets
  - Walk speed decreases progressively during charge
  - Visual: Particle effects during charge, beam entity on release
  - Audio: Ambient charging sound

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.ac.ability.util.charge :as charge]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :meltdowner :exp] 0.0)))

(defn meltdowner-on-key-down
  "Initialize charge state when key pressed."
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (get-skill-exp player-id)
          ;; Charge timing: 20-100 ticks
          min-ticks 20
          max-ticks 100
          optimal-ticks 40  ; Peak damage at 40 ticks (2 seconds)

          charge-state (charge/init-charge-state min-ticks max-ticks optimal-ticks)]

      ;; Store charge state in context
      (ctx/update-context! ctx-id assoc :skill-state {:charge charge-state})

      (log/debug "Meltdowner charge started"))
    (catch Exception e
      (log/warn "Meltdowner key-down failed:" (ex-message e)))))

(defn meltdowner-on-key-tick
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
                                       :meltdowner
                                       0.0001
                                       1.0)]
            (ps/update-ability-data! player-id (constantly data))
            (doseq [e events]
              (ability-evt/fire-ability-event! e))))

        ;; Log progress every 20 ticks
        (when (zero? (mod (:charge-ticks new-charge-state) 20))
          (log/debug "Meltdowner charging:" (int (* progress 100)) "%"))))
    (catch Exception e
      (log/warn "Meltdowner key-tick failed:" (ex-message e)))))

(defn meltdowner-on-key-up
  "Fire Meltdowner beam when key released."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            charge-state (:charge skill-state)
            exp (get-skill-exp player-id)]

        ;; Check if minimum charge reached
        (if-not (charge/is-charge-complete? charge-state)
          (log/debug "Meltdowner: Insufficient charge")

          (let [;; Calculate charge multiplier
                charge-mult (charge/get-charge-multiplier charge-state)

                ;; Scale parameters by experience and charge
                base-damage (scaling/scale-damage 18.0 50.0 exp)
                damage (* base-damage charge-mult)

                ;; Range scales with experience
                max-range (scaling/scale-range 60.0 120.0 exp)

                ;; Max reflections
                max-reflections 3

                ;; Get player look vector and position
                look-vec (when raycast/*raycast*
                           (raycast/get-player-look-vector raycast/*raycast* player-id))
                player-state (ps/get-player-state player-id)
                player-pos (get player-state :position {:x 0.0 :y 64.0 :z 0.0})]

            (if-not look-vec
              (log/warn "Meltdowner: Could not get player look vector")

              (let [{:keys [x y z]} player-pos
                    {:keys [x dx y dy z dz]} look-vec

                    ;; Raycast to find target
                    hit (when raycast/*raycast*
                          (raycast/raycast-combined raycast/*raycast*
                                                    "minecraft:overworld"
                                                    x (+ y 1.6) z
                                                    dx dy dz
                                                    max-range))]

                (if-not hit
                  (log/debug "Meltdowner: No target found")

                  (let [hit-type (:hit-type hit)]

                    (if (= hit-type :entity)
                      ;; Hit entity - apply reflection damage
                      (let [entity-uuid (:uuid hit)

                            ;; Apply reflection damage
                            hit-entities (when entity-damage/*entity-damage*
                                           (entity-damage/apply-reflection-damage!
                                            entity-damage/*entity-damage*
                                            "minecraft:overworld"
                                            entity-uuid
                                            damage
                                            :magic
                                            0
                                            max-reflections))]

                        ;; Grant experience based on hits
                        (when (seq hit-entities)
                          (when-let [state (ps/get-player-state player-id)]
                            (let [exp-gain (* 0.015 charge-mult (count hit-entities))
                                  {:keys [data events]} (learning/add-skill-exp
                                                         (:ability-data state)
                                                         player-id
                                                         :meltdowner
                                                         exp-gain
                                                         1.0)]
                              (ps/update-ability-data! player-id (constantly data))
                              (doseq [e events]
                                (ability-evt/fire-ability-event! e)))))

                        (log/debug "Meltdowner fired: damage" (int damage)
                                   "mult" (format "%.2f" charge-mult)
                                   "hit" (count hit-entities) "entities"))

                      ;; Hit block - no damage but grant small experience
                      (do
                        (when-let [state (ps/get-player-state player-id)]
                          (let [exp-gain (* 0.005 charge-mult)
                                {:keys [data events]} (learning/add-skill-exp
                                                       (:ability-data state)
                                                       player-id
                                                       :meltdowner
                                                       exp-gain
                                                       1.0)]
                            (ps/update-ability-data! player-id (constantly data))
                            (doseq [e events]
                              (ability-evt/fire-ability-event! e))))

                        (log/debug "Meltdowner fired: hit block")))))))))))
    (catch Exception e
      (log/warn "Meltdowner key-up failed:" (ex-message e)))))

(defn meltdowner-on-key-abort
  "Clean up charge state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "Meltdowner charge aborted")
    (catch Exception e
      (log/warn "Meltdowner key-abort failed:" (ex-message e)))))
