(ns cn.li.ac.content.ability.vecmanip.plasma-cannon
  "PlasmaCannon - Level 5 Vector Manipulation skill.

  Charged plasma projectile that creates massive explosion.

  Mechanics:
  - Charge 60-30 ticks (scales with exp)
  - Spawns plasma projectile 15 blocks above player
  - Projectile travels to raycast target at 1 block/tick
  - Max flight time: 240 ticks
  - Explosion radius: 12-15 blocks
  - Damage: 80-150 in 10 block radius

  Resources:
  - CP: 18-25 per tick during charge (scales UP with exp)
  - Overload: 500-400 maintained (scales down with exp)
  - Cooldown: 1000-600 ticks (scales down with exp)

  Experience:
  - 0.008 on successful cast

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :plasma-cannon :exp] 0.0)))

(defn- get-player-position [player-id]
  "Get player position from teleportation protocol."
  (when-let [teleportation (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
    (when-let [tp-impl @teleportation]
      ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) tp-impl player-id))))

(defn plasma-cannon-on-key-down
  "Initialize charge state."
  [{:keys [ctx-id player-id]}]
  (try
    (let [exp (get-skill-exp player-id)
          charge-ticks-needed (int (scaling/lerp 60.0 30.0 exp))]
      (ctx/update-context! ctx-id assoc :skill-state
                           {:charge-ticks 0
                            :charge-ticks-needed charge-ticks-needed
                            :charged false})
      (log/debug "PlasmaCannon: Charge started, need" charge-ticks-needed "ticks"))
    (catch Exception e
      (log/warn "PlasmaCannon key-down failed:" (ex-message e)))))

(defn plasma-cannon-on-key-tick
  "Update charge progress."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (:charge-ticks skill-state 0)
            charge-ticks-needed (:charge-ticks-needed skill-state 60)
            charged (:charged skill-state false)]

        (when-not charged
          ;; Increment charge ticks
          (ctx/update-context! ctx-id update-in [:skill-state :charge-ticks] inc)

          ;; Mark as charged when ready
          (when (>= charge-ticks charge-ticks-needed)
            (ctx/update-context! ctx-id assoc-in [:skill-state :charged] true)
            (log/debug "PlasmaCannon: Fully charged"))

          ;; Grant minimal experience during charge
          (when-let [state (ps/get-player-state player-id)]
            (let [{:keys [data events]} (learning/add-skill-exp
                                         (:ability-data state)
                                         player-id
                                         :plasma-cannon
                                         0.00001
                                         1.0)]
              (ps/update-ability-data! player-id (constantly data))
              (doseq [e events]
                (ability-evt/fire-ability-event! e)))))))
    (catch Exception e
      (log/warn "PlasmaCannon key-tick failed:" (ex-message e)))))

(defn plasma-cannon-on-key-up
  "Fire plasma cannon when key is released."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charged (:charged skill-state false)
            exp (get-skill-exp player-id)]

        (if charged
          (when-let [pos (get-player-position player-id)]
            (let [world-id (:world-id pos)
                  start-x (:x pos)
                  start-y (+ (:y pos) 15.0)  ; 15 blocks above player
                  start-z (:z pos)]

              ;; Get player look direction for raycast target
              (when raycast/*raycast*
                (when-let [look-vec (raycast/get-player-look-vector raycast/*raycast* player-id)]
                  (let [dir-x (:x look-vec)
                        dir-y (:y look-vec)
                        dir-z (:z look-vec)
                        ;; Raycast to find target (max 240 blocks)
                        hit (raycast/raycast-combined raycast/*raycast*
                                                      world-id
                                                      start-x (+ (:y pos) 1.6) start-z
                                                      dir-x dir-y dir-z
                                                      240.0)]

                    (if hit
                      (let [target-x (get-in hit [:x] start-x)
                            target-y (get-in hit [:y] (:y pos))
                            target-z (get-in hit [:z] start-z)
                            damage (scaling/lerp 80.0 150.0 exp)
                            explosion-radius (scaling/lerp 12.0 15.0 exp)]

                        ;; Create explosion at target
                        (when world-effects/*world-effects*
                          (world-effects/create-explosion! world-effects/*world-effects*
                                                          world-id
                                                          target-x target-y target-z
                                                          explosion-radius
                                                          true))  ; Destroy blocks

                        ;; Damage entities in 10 block radius
                        (when world-effects/*world-effects*
                          (let [entities (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                                                world-id
                                                                                (double target-x)
                                                                                (double target-y)
                                                                                (double target-z)
                                                                                10.0)]
                            (doseq [entity entities]
                              (let [entity-id (:uuid entity)]
                                (when-not (= entity-id player-id)
                                  (when entity-damage/*entity-damage*
                                    (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                                       entity-id
                                                                       player-id
                                                                       damage
                                                                       "plasma_cannon")))))))

                        ;; Grant experience
                        (when-let [state (ps/get-player-state player-id)]
                          (let [{:keys [data events]} (learning/add-skill-exp
                                                       (:ability-data state)
                                                       player-id
                                                       :plasma-cannon
                                                       0.008
                                                       1.0)]
                            (ps/update-ability-data! player-id (constantly data))
                            (doseq [e events]
                              (ability-evt/fire-ability-event! e))))

                        (log/info "PlasmaCannon: Fired at" [target-x target-y target-z] "damage:" (int damage)))

                      (log/debug "PlasmaCannon: No target found")))))))

          (log/debug "PlasmaCannon: Released before fully charged, aborting"))))
    (catch Exception e
      (log/warn "PlasmaCannon key-up failed:" (ex-message e)))))

(defn plasma-cannon-on-key-abort
  "Clean up state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "PlasmaCannon aborted")
    (catch Exception e
      (log/warn "PlasmaCannon key-abort failed:" (ex-message e)))))
