(ns cn.li.ac.content.ability.vecmanip.directed-blastwave
  "DirectedBlastwave - Level 3 Vector Manipulation skill.

  Charged ranged AOE shockwave that damages entities and breaks blocks.

  Mechanics:
  - Charge 6-50 ticks (0.3-2.5 seconds)
  - Raycast 4 blocks to find target position
  - AOE damage 10-25 in 3 block radius
  - Knockback entities with -1.2 velocity
  - Break blocks in 6 block radius (hardness based on exp)
  - Drop rate 40-90%

  Resources:
  - CP: 160-200 (scales down with exp)
  - Overload: 50-30 (scales down with exp)
  - Cooldown: 80-50 ticks (scales down with exp)

  Experience:
  - 0.0025 on hit
  - 0.0012 on miss

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :directed-blastwave :exp] 0.0)))

(defn- get-player-position [player-id]
  "Get player position from teleportation protocol."
  (when-let [teleportation (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
    (when-let [tp-impl @teleportation]
      ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) tp-impl player-id))))

(defn directed-blastwave-on-key-down
  "Initialize charge state."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id assoc :skill-state
                         {:charge-ticks 0})
    (log/debug "DirectedBlastwave: Charge started")
    (catch Exception e
      (log/warn "DirectedBlastwave key-down failed:" (ex-message e)))))

(defn directed-blastwave-on-key-tick
  "Update charge progress."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (:charge-ticks skill-state 0)]

        ;; Increment charge ticks
        (ctx/update-context! ctx-id update-in [:skill-state :charge-ticks] inc)

        ;; Auto-release at max charge (50 ticks)
        (when (>= charge-ticks 50)
          (log/debug "DirectedBlastwave: Max charge reached, auto-releasing")
          ;; Trigger release by aborting (will be handled in key-up)
          (ctx/update-context! ctx-id assoc-in [:skill-state :auto-release] true))

        ;; Grant minimal experience during charge
        (when-let [state (ps/get-player-state player-id)]
          (let [{:keys [data events]} (learning/add-skill-exp
                                       (:ability-data state)
                                       player-id
                                       :directed-blastwave
                                       0.00001
                                       1.0)]
            (ps/update-ability-data! player-id (constantly data))
            (doseq [e events]
              (ability-evt/fire-ability-event! e))))))
    (catch Exception e
      (log/warn "DirectedBlastwave key-tick failed:" (ex-message e)))))

(defn directed-blastwave-on-key-up
  "Execute blastwave when key is released."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (:charge-ticks skill-state 0)
            exp (get-skill-exp player-id)]

        ;; Check if charge is valid (6+ ticks)
        (if (>= charge-ticks 6)
          (when-let [pos (get-player-position player-id)]
            (let [world-id (:world-id pos)
                  start-x (:x pos)
                  start-y (+ (:y pos) 1.6)  ; Eye height
                  start-z (:z pos)]

              ;; Get player look direction
              (when raycast/*raycast*
                (when-let [look-vec (raycast/get-player-look-vector raycast/*raycast* player-id)]
                  (let [dir-x (:x look-vec)
                        dir-y (:y look-vec)
                        dir-z (:z look-vec)
                        ;; Raycast 4 blocks to find target
                        hit (raycast/raycast-blocks raycast/*raycast*
                                                    world-id
                                                    start-x start-y start-z
                                                    dir-x dir-y dir-z
                                                    4.0)]

                    (if hit
                      (let [target-x (:x hit)
                            target-y (:y hit)
                            target-z (:z hit)
                            ;; Calculate damage based on charge
                            charge-progress (min 1.0 (/ (- charge-ticks 6) (- 50 6)))
                            base-damage (scaling/scale-damage 10.0 25.0 exp)
                            charge-multiplier (+ 0.8 (* 0.4 charge-progress))
                            damage (* base-damage charge-multiplier)
                            hardness (scaling/lerp 1.0 5.0 exp)
                            drop-rate (scaling/lerp 0.4 0.9 exp)]

                        ;; AOE damage in 3 block radius
                        (when world-effects/*world-effects*
                          (let [entities (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                                                world-id
                                                                                (double target-x)
                                                                                (double target-y)
                                                                                (double target-z)
                                                                                3.0)]
                            (doseq [entity entities]
                              (let [entity-id (:uuid entity)]
                                (when-not (= entity-id player-id)
                                  ;; Damage entity
                                  (when entity-damage/*entity-damage*
                                    (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                                       entity-id
                                                                       player-id
                                                                       damage
                                                                       "directed_blastwave"))

                                  ;; Apply knockback
                                  (when player-motion/*player-motion*
                                    (player-motion/add-velocity! player-motion/*player-motion*
                                                                entity-id
                                                                0.0
                                                                -1.2
                                                                0.0)))))))

                        ;; Break blocks in 6 block radius
                        (when block-manip/*block-manipulation*
                          (let [tx (int target-x)
                                ty (int target-y)
                                tz (int target-z)]
                            (doseq [x (range (- tx 6) (+ tx 7))
                                    y (range (- ty 6) (+ ty 7))
                                    z (range (- tz 6) (+ tz 7))]
                              (when-let [block-hardness (block-manip/get-block-hardness
                                                         block-manip/*block-manipulation*
                                                         world-id x y z)]
                                (when (<= block-hardness hardness)
                                  (when (< (rand) drop-rate)
                                    (block-manip/break-block! block-manip/*block-manipulation*
                                                             player-id world-id x y z true)))))))

                        ;; Grant experience
                        (when-let [state (ps/get-player-state player-id)]
                          (let [{:keys [data events]} (learning/add-skill-exp
                                                       (:ability-data state)
                                                       player-id
                                                       :directed-blastwave
                                                       0.0025
                                                       1.0)]
                            (ps/update-ability-data! player-id (constantly data))
                            (doseq [e events]
                              (ability-evt/fire-ability-event! e))))

                        (log/info "DirectedBlastwave: Hit at" [target-x target-y target-z] "damage:" (int damage)))

                      ;; Missed
                      (do
                        (when-let [state (ps/get-player-state player-id)]
                          (let [{:keys [data events]} (learning/add-skill-exp
                                                       (:ability-data state)
                                                       player-id
                                                       :directed-blastwave
                                                       0.0012
                                                       1.0)]
                            (ps/update-ability-data! player-id (constantly data))
                            (doseq [e events]
                              (ability-evt/fire-ability-event! e))))
                        (log/debug "DirectedBlastwave: Missed"))))))))

          ;; Invalid charge time
          (log/debug "DirectedBlastwave: Invalid charge time" charge-ticks))))
    (catch Exception e
      (log/warn "DirectedBlastwave key-up failed:" (ex-message e)))))

(defn directed-blastwave-on-key-abort
  "Clean up state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "DirectedBlastwave aborted")
    (catch Exception e
      (log/warn "DirectedBlastwave key-abort failed:" (ex-message e)))))
