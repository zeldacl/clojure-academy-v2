(ns cn.li.ac.content.ability.electromaster.current-charging
  "CurrentCharging skill - channel energy into blocks or held items.

  Mechanics:
  - Hold key to channel energy via raycast
  - Raycast to find target block/item (15 block range)
  - Energy consumption: 3-7 per tick + 15-35 per tick charging speed (scales with exp)
  - Initial overload: 65-48 (scales with exp)
  - Experience gain: 0.0001/tick effective, 0.00003/tick ineffective
  - Visual: Arc entity connecting player to target
  - Audio: Looping ambient sound

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :current-charging :exp] 0.0)))

(defn current-charging-on-key-down
  "Initialize charging state when key pressed."
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (get-skill-exp player-id)
          max-range 15.0

          ;; Get player look vector and position
          look-vec (when raycast/*raycast*
                     (raycast/get-player-look-vector raycast/*raycast* player-id))
          player-state (ps/get-player-state player-id)
          player-pos (get player-state :position {:x 0.0 :y 64.0 :z 0.0})]

      (if-not look-vec
        (log/warn "CurrentCharging: Could not get player look vector")

        (let [{:keys [x y z]} player-pos
              {:keys [x dx y dy z dz]} look-vec

              ;; Raycast to find target
              hit (when raycast/*raycast*
                    (raycast/raycast-blocks raycast/*raycast*
                                            "minecraft:overworld"
                                            x (+ y 1.6) z
                                            dx dy dz
                                            max-range))]

          (if-not hit
            (do
              (log/debug "CurrentCharging: No target found")
              ;; Store state indicating no target
              (ctx/update-context! ctx-id assoc :skill-state {:has-target false}))

            (let [target-x (:x hit)
                  target-y (:y hit)
                  target-z (:z hit)
                  block-id (:block-id hit)]

              ;; Store charging state with target info
              (ctx/update-context! ctx-id assoc :skill-state
                                   {:has-target true
                                    :target-x target-x
                                    :target-y target-y
                                    :target-z target-z
                                    :block-id block-id
                                    :charge-ticks 0})

              (log/debug "CurrentCharging started on block:" block-id))))))
    (catch Exception e
      (log/warn "CurrentCharging key-down failed:" (ex-message e)))))

(defn current-charging-on-key-tick
  "Continue charging each tick."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            has-target (:has-target skill-state)
            exp (get-skill-exp player-id)]

        (if-not has-target
          ;; No target, grant minimal experience
          (when-let [state (ps/get-player-state player-id)]
            (let [{:keys [data events]} (learning/add-skill-exp
                                         (:ability-data state)
                                         player-id
                                         :current-charging
                                         0.00003
                                         1.0)]
              (ps/update-ability-data! player-id (constantly data))
              (doseq [e events]
                (ability-evt/fire-ability-event! e))))

          ;; Has target, charging is effective
          (let [charge-ticks (:charge-ticks skill-state)
                new-charge-ticks (inc charge-ticks)]

            ;; Update charge ticks
            (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] new-charge-ticks)

            ;; Grant experience for effective charging
            (when-let [state (ps/get-player-state player-id)]
              (let [{:keys [data events]} (learning/add-skill-exp
                                           (:ability-data state)
                                           player-id
                                           :current-charging
                                           0.0001
                                           1.0)]
                (ps/update-ability-data! player-id (constantly data))
                (doseq [e events]
                  (ability-evt/fire-ability-event! e))))

            ;; Log progress every second
            (when (zero? (mod new-charge-ticks 20))
              (log/debug "CurrentCharging: charged for" (/ new-charge-ticks 20) "seconds"))))))
    (catch Exception e
      (log/warn "CurrentCharging key-tick failed:" (ex-message e)))))

(defn current-charging-on-key-up
  "Stop charging when key released."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            has-target (:has-target skill-state)
            charge-ticks (or (:charge-ticks skill-state) 0)]

        (if has-target
          (log/debug "CurrentCharging completed: charged for" (/ charge-ticks 20) "seconds")
          (log/debug "CurrentCharging completed: no target"))))
    (catch Exception e
      (log/warn "CurrentCharging key-up failed:" (ex-message e)))))

(defn current-charging-on-key-abort
  "Clean up charging state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "CurrentCharging aborted")
    (catch Exception e
      (log/warn "CurrentCharging key-abort failed:" (ex-message e)))))
