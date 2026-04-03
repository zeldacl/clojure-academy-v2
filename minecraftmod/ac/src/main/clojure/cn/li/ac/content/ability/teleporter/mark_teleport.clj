(ns cn.li.ac.content.ability.teleporter.mark-teleport
  "MarkTeleport skill - teleport to look direction target.

  Mechanics:
  - Raycast along look direction to find destination (3 block minimum)
  - Hold key to show destination marker (client-side visual)
  - Release to teleport to destination
  - Energy: 12-4 CP per block traveled (scales with exp) + 40-20 overload
  - Cooldown: 30-0 ticks (scales inversely with exp)
  - Experience gain: 0.00018 × distance
  - Resets fall damage on completion
  - Dismounts rideable entities

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :mark-teleport :exp] 0.0)))

(defn- calculate-distance [x1 y1 z1 x2 y2 z2]
  (Math/sqrt (+ (* (- x2 x1) (- x2 x1))
                (* (- y2 y1) (- y2 y1))
                (* (- z2 z1) (- z2 z1)))))

(defn mark-teleport-on-key-down
  "Find teleport destination when key pressed."
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (get-skill-exp player-id)
          ;; Max range scales with experience
          max-range (scaling/scale-range 20.0 50.0 exp)
          min-distance 3.0

          ;; Get player position
          player-pos (when teleportation/*teleportation*
                       (teleportation/get-player-position teleportation/*teleportation* player-id))

          ;; Get player look vector
          look-vec (when raycast/*raycast*
                     (raycast/get-player-look-vector raycast/*raycast* player-id))]

      (if (or (not player-pos) (not look-vec))
        (do
          (log/warn "MarkTeleport: Could not get player position or look vector")
          (ctx/update-context! ctx-id assoc :skill-state {:has-target false}))

        (let [{:keys [world-id x y z]} player-pos
              {:keys [x dx y dy z dz]} look-vec

              ;; Raycast to find destination
              hit (when raycast/*raycast*
                    (raycast/raycast-blocks raycast/*raycast*
                                            world-id
                                            x (+ y 1.6) z
                                            dx dy dz
                                            max-range))]

          (if-not hit
            (do
              (log/debug "MarkTeleport: No target found")
              (ctx/update-context! ctx-id assoc :skill-state {:has-target false}))

            (let [target-x (double (:x hit))
                  target-y (double (:y hit))
                  target-z (double (:z hit))
                  face (:face hit)

                  ;; Adjust landing position based on hit face
                  ;; Place player on top of block or in front of wall
                  [final-x final-y final-z] (case face
                                              :up [target-x (+ target-y 1.0) target-z]
                                              :down [target-x (- target-y 2.0) target-z]
                                              :north [target-x target-y (- target-z 1.0)]
                                              :south [target-x target-y (+ target-z 1.0)]
                                              :west [(- target-x 1.0) target-y target-z]
                                              :east [(+ target-x 1.0) target-y target-z]
                                              [target-x (+ target-y 1.0) target-z])

                  distance (calculate-distance x y z final-x final-y final-z)]

              (if (< distance min-distance)
                (do
                  (log/debug "MarkTeleport: Target too close" (int distance) "blocks")
                  (ctx/update-context! ctx-id assoc :skill-state {:has-target false}))

                (do
                  ;; Store destination
                  (ctx/update-context! ctx-id assoc :skill-state
                                       {:has-target true
                                        :world-id world-id
                                        :target-x final-x
                                        :target-y final-y
                                        :target-z final-z
                                        :distance distance
                                        :start-x x
                                        :start-y y
                                        :start-z z})

                  (log/debug "MarkTeleport: Target found at distance" (int distance) "blocks"))))))))
    (catch Exception e
      (log/warn "MarkTeleport key-down failed:" (ex-message e)))))

(defn mark-teleport-on-key-tick
  "Update destination marker (visual feedback)."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            has-target (:has-target skill-state)]

        (when has-target
          ;; Grant minimal experience during hold
          (when-let [state (ps/get-player-state player-id)]
            (let [{:keys [data events]} (learning/add-skill-exp
                                         (:ability-data state)
                                         player-id
                                         :mark-teleport
                                         0.00001
                                         1.0)]
              (ps/update-ability-data! player-id (constantly data))
              (doseq [e events]
                (ability-evt/fire-ability-event! e))))

          ;; TODO: Send marker position to client for visual rendering
          )))
    (catch Exception e
      (log/warn "MarkTeleport key-tick failed:" (ex-message e)))))

(defn mark-teleport-on-key-up
  "Execute teleport when key released."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            has-target (:has-target skill-state)]

        (if-not has-target
          (log/debug "MarkTeleport: No valid target")

          (let [world-id (:world-id skill-state)
                target-x (:target-x skill-state)
                target-y (:target-y skill-state)
                target-z (:target-z skill-state)
                distance (:distance skill-state)]

            ;; Teleport player
            (when teleportation/*teleportation*
              (let [success (teleportation/teleport-player! teleportation/*teleportation*
                                                            player-id
                                                            world-id
                                                            target-x
                                                            target-y
                                                            target-z)]

                (when success
                  ;; Reset fall damage
                  (teleportation/reset-fall-damage! teleportation/*teleportation* player-id)

                  ;; Grant experience based on distance
                  (when-let [state (ps/get-player-state player-id)]
                    (let [exp-gain (* 0.00018 distance)
                          {:keys [data events]} (learning/add-skill-exp
                                                 (:ability-data state)
                                                 player-id
                                                 :mark-teleport
                                                 exp-gain
                                                 1.0)]
                      (ps/update-ability-data! player-id (constantly data))
                      (doseq [e events]
                        (ability-evt/fire-ability-event! e))))

                  (log/debug "MarkTeleport: Teleported" (int distance) "blocks"))))))))
    (catch Exception e
      (log/warn "MarkTeleport key-up failed:" (ex-message e)))))

(defn mark-teleport-on-key-abort
  "Clean up teleport state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "MarkTeleport aborted")
    (catch Exception e
      (log/warn "MarkTeleport key-abort failed:" (ex-message e)))))
