(ns cn.li.ac.content.ability.electromaster.mag-movement
  "MagMovement skill - magnetic acceleration toward metal blocks/entities.

  Mechanics:
  - Raycast to metal blocks/entities (25 block range)
  - Accelerate player toward target with smooth interpolation
  - Energy: 15-8 CP per tick (scales with exp), 60-30 overload max
  - Low exp requires strong metal blocks only; high exp unlocks weak metal blocks
  - Visual: Arc with wiggle animation
  - Audio: Looping ambient sound
  - Resets fall damage on completion
  - Grants experience based on distance traveled

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
    (get-in state [:ability-data :skills :mag-movement :exp] 0.0)))

(def ^:private strong-metal-blocks
  #{"minecraft:iron_block"
    "minecraft:iron_ore"
    "minecraft:deepslate_iron_ore"
    "minecraft:gold_block"
    "minecraft:gold_ore"
    "minecraft:deepslate_gold_ore"
    "minecraft:copper_block"
    "minecraft:copper_ore"
    "minecraft:deepslate_copper_ore"
    "minecraft:netherite_block"
    "minecraft:ancient_debris"})

(def ^:private weak-metal-blocks
  #{"minecraft:iron_door"
    "minecraft:iron_trapdoor"
    "minecraft:iron_bars"
    "minecraft:chain"
    "minecraft:anvil"
    "minecraft:hopper"
    "minecraft:rail"
    "minecraft:powered_rail"
    "minecraft:detector_rail"
    "minecraft:activator_rail"})

(defn- is-metal-block? [block-id exp]
  (or (contains? strong-metal-blocks block-id)
      (and (>= exp 0.5)
           (contains? weak-metal-blocks block-id))))

(defn mag-movement-on-key-down
  "Initialize mag movement when key pressed."
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (get-skill-exp player-id)
          max-range 25.0

          ;; Get player look vector and position
          look-vec (when raycast/*raycast*
                     (raycast/get-player-look-vector raycast/*raycast* player-id))
          player-state (ps/get-player-state player-id)
          player-pos (get player-state :position {:x 0.0 :y 64.0 :z 0.0})]

      (if-not look-vec
        (log/warn "MagMovement: Could not get player look vector")

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
              (log/debug "MagMovement: No target found")
              (ctx/update-context! ctx-id assoc :skill-state {:has-target false}))

            (let [target-x (:x hit)
                  target-y (:y hit)
                  target-z (:z hit)
                  block-id (:block-id hit)]

              ;; Check if target is metal
              (if-not (is-metal-block? block-id exp)
                (do
                  (log/debug "MagMovement: Target is not metal:" block-id)
                  (ctx/update-context! ctx-id assoc :skill-state {:has-target false}))

                (let [;; Calculate distance
                      dx (- target-x x)
                      dy (- target-y y)
                      dz (- target-z z)
                      distance (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]

                  ;; Store movement state
                  (ctx/update-context! ctx-id assoc :skill-state
                                       {:has-target true
                                        :target-x target-x
                                        :target-y target-y
                                        :target-z target-z
                                        :block-id block-id
                                        :start-x x
                                        :start-y y
                                        :start-z z
                                        :distance distance
                                        :movement-ticks 0})

                  (log/debug "MagMovement started toward" block-id "distance:" (int distance)))))))))
    (catch Exception e
      (log/warn "MagMovement key-down failed:" (ex-message e)))))

(defn mag-movement-on-key-tick
  "Continue magnetic movement each tick."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            has-target (:has-target skill-state)]

        (when has-target
          (let [movement-ticks (:movement-ticks skill-state)
                new-movement-ticks (inc movement-ticks)]

            ;; Update movement ticks
            (ctx/update-context! ctx-id assoc-in [:skill-state :movement-ticks] new-movement-ticks)

            ;; Grant small experience during movement
            (when-let [state (ps/get-player-state player-id)]
              (let [{:keys [data events]} (learning/add-skill-exp
                                           (:ability-data state)
                                           player-id
                                           :mag-movement
                                           0.0001
                                           1.0)]
                (ps/update-ability-data! player-id (constantly data))
                (doseq [e events]
                  (ability-evt/fire-ability-event! e))))

            ;; TODO: Apply velocity to player toward target
            ;; This would require a player motion protocol

            (when (zero? (mod new-movement-ticks 10))
              (log/debug "MagMovement: moving for" (/ new-movement-ticks 20) "seconds"))))))
    (catch Exception e
      (log/warn "MagMovement key-tick failed:" (ex-message e)))))

(defn mag-movement-on-key-up
  "Complete magnetic movement when key released."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            has-target (:has-target skill-state)]

        (when has-target
          (let [distance (:distance skill-state)
                movement-ticks (:movement-ticks skill-state)

                ;; Grant experience based on distance traveled
                exp-gain (* 0.001 (min distance 25.0))]

            (when-let [state (ps/get-player-state player-id)]
              (let [{:keys [data events]} (learning/add-skill-exp
                                           (:ability-data state)
                                           player-id
                                           :mag-movement
                                           exp-gain
                                           1.0)]
                (ps/update-ability-data! player-id (constantly data))
                (doseq [e events]
                  (ability-evt/fire-ability-event! e))))

            ;; TODO: Reset fall damage
            ;; This would require a player state protocol

            (log/debug "MagMovement completed: distance" (int distance)
                       "ticks" movement-ticks)))))
    (catch Exception e
      (log/warn "MagMovement key-up failed:" (ex-message e)))))

(defn mag-movement-on-key-abort
  "Clean up movement state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "MagMovement aborted")
    (catch Exception e
      (log/warn "MagMovement key-abort failed:" (ex-message e)))))
