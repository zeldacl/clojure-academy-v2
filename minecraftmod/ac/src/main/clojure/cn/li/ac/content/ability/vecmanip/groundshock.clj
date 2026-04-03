(ns cn.li.ac.content.ability.vecmanip.groundshock
  "Groundshock skill - ground slam AOE attack.

  Mechanics:
  - Requires player on ground
  - Charge for 5+ ticks (affects pitch animation)
  - Propagates shockwave in look direction
  - Breaks/modifies blocks in path (stone→cobblestone, grass→dirt)
  - Damages entities in AOE (4-6 damage)
  - Launches entities upward (0.6-0.9 y velocity)
  - At 100% experience: breaks blocks in 5-block radius around player
  - Energy system: starts with 60-120 energy, consumes per block/entity
  - CP: 80-150, Overload: 15-10, Cooldown: 80-40 ticks
  - Max iterations: 10-25 (based on experience)
  - Drop rate: 30-100% (based on experience)

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :groundshock :exp] 0.0)))

(defn- get-player-position [player-id]
  "Get player position from teleportation protocol."
  (when-let [teleportation (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
    (when-let [tp-impl @teleportation]
      ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) tp-impl player-id))))

(defn groundshock-on-key-down
  "Initialize charge state."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id assoc :skill-state
                         {:charge-ticks 0})
    (log/debug "Groundshock: Charge started")
    (catch Exception e
      (log/warn "Groundshock key-down failed:" (ex-message e)))))

(defn groundshock-on-key-tick
  "Update charge progress."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (:charge-ticks skill-state 0)]

        ;; Increment charge ticks
        (ctx/update-context! ctx-id update-in [:skill-state :charge-ticks] inc)

        ;; Grant minimal experience during charge
        (when-let [state (ps/get-player-state player-id)]
          (let [{:keys [data events]} (learning/add-skill-exp
                                       (:ability-data state)
                                       player-id
                                       :groundshock
                                       0.00001
                                       1.0)]
            (ps/update-ability-data! player-id (constantly data))
            (doseq [e events]
              (ability-evt/fire-ability-event! e))))))
    (catch Exception e
      (log/warn "Groundshock key-tick failed:" (ex-message e)))))

(defn- propagate-shockwave!
  "Propagate shockwave along ground in look direction."
  [player-id world-id start-x start-y start-z dir-x dir-z exp]
  (let [init-energy (scaling/lerp 60.0 120.0 exp)
        max-iter (int (scaling/lerp 10.0 25.0 exp))
        damage (scaling/scale-damage 4.0 6.0 exp)
        drop-rate (scaling/lerp 0.3 1.0 exp)
        y-speed (scaling/lerp 0.6 0.9 exp)]

    (loop [energy init-energy
           iter 0
           x start-x
           z start-z
           affected-blocks #{}
           affected-entities #{}]

      (if (or (<= energy 0) (>= iter max-iter))
        {:affected-blocks affected-blocks
         :affected-entities affected-entities}

        (let [;; Current position (floor to block coords)
              block-x (int (Math/floor x))
              block-y (int (Math/floor start-y))
              block-z (int (Math/floor z))

              ;; Check blocks around current position
              positions [[(+ block-x 0) block-y (+ block-z 0)]
                        [(+ block-x 1) block-y (+ block-z 0)]
                        [(+ block-x -1) block-y (+ block-z 0)]
                        [(+ block-x 0) block-y (+ block-z 1)]
                        [(+ block-x 0) block-y (+ block-z -1)]]

              ;; Process blocks
              new-energy (atom energy)
              new-affected-blocks (atom affected-blocks)
              new-affected-entities (atom affected-entities)]

          ;; Process each position
          (doseq [[bx by bz] positions]
            (let [pos-key [bx by bz]]
              (when-not (contains? @new-affected-blocks pos-key)
                (when block-manip/*block-manipulation*
                  (when-let [block-id (block-manip/get-block block-manip/*block-manipulation*
                                                              world-id bx by bz)]
                    (when-let [hardness (block-manip/get-block-hardness block-manip/*block-manipulation*
                                                                         world-id bx by bz)]
                      (when (and (>= hardness 0) (<= hardness 5.0))
                        ;; Modify block based on type
                        (cond
                          (= block-id "minecraft:stone")
                          (do
                            (block-manip/set-block! block-manip/*block-manipulation*
                                                   world-id bx by bz "minecraft:cobblestone")
                            (swap! new-energy - 0.4))

                          (= block-id "minecraft:grass_block")
                          (do
                            (block-manip/set-block! block-manip/*block-manipulation*
                                                   world-id bx by bz "minecraft:dirt")
                            (swap! new-energy - 0.2))

                          :else
                          (swap! new-energy - 0.5))

                        (swap! new-affected-blocks conj pos-key))))))))

          ;; Find and damage entities in area
          (when world-effects/*world-effects*
            (let [entities (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                                  world-id
                                                                  (double block-x)
                                                                  (double (+ block-y 1))
                                                                  (double block-z)
                                                                  2.0)]
              (doseq [entity entities]
                (let [entity-id (:uuid entity)]
                  (when-not (or (= entity-id player-id)
                               (contains? @new-affected-entities entity-id))
                    ;; Damage entity
                    (when entity-damage/*entity-damage*
                      (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                         entity-id
                                                         player-id
                                                         damage
                                                         "groundshock"))

                    ;; Launch entity upward
                    (when player-motion/*player-motion*
                      (player-motion/add-velocity! player-motion/*player-motion*
                                                  entity-id
                                                  0.0
                                                  y-speed
                                                  0.0))

                    (swap! new-energy - 1.0)
                                (swap! new-affected-entities conj entity-id))))))

          ;; Move forward
          (recur @new-energy
                 (inc iter)
                 (+ x dir-x)
                 (+ z dir-z)
                 @new-affected-blocks
                              @new-affected-entities))))))

(defn groundshock-on-key-up
  "Perform the ground slam."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (:charge-ticks skill-state 0)
            exp (get-skill-exp player-id)]

        ;; Check if charge is valid (5+ ticks) and player is on ground
        (if (and (>= charge-ticks 5)
                 player-motion/*player-motion*
                 (player-motion/is-on-ground? player-motion/*player-motion* player-id))

          (when-let [pos (get-player-position player-id)]
            (let [world-id (:world-id pos)
                  start-x (:x pos)
                  start-y (- (:y pos) 1.0)  ; One block below player
                  start-z (:z pos)]

              ;; Get player look direction (horizontal only)
              (when raycast/*raycast*
                (when-let [look-vec (raycast/get-player-look-vector raycast/*raycast* player-id)]
                  (let [dir-x (:x look-vec)
                        dir-z (:z look-vec)
                        ;; Normalize horizontal direction
                        len (Math/sqrt (+ (* dir-x dir-x) (* dir-z dir-z)))
                        norm-x (/ dir-x len)
                        norm-z (/ dir-z len)]

                    ;; Propagate shockwave
                    (let [result (propagate-shockwave! player-id world-id
                                                      start-x start-y start-z
                                                      norm-x norm-z
                                                      exp)
                          affected-count (+ (count (:affected-blocks result))
                                          (count (:affected-entities result)))]

                      ;; At 100% experience, break blocks in radius
                      (when (and (>= exp 1.0) block-manip/*block-manipulation*)
                        (let [px (int start-x)
                              py (int start-y)
                              pz (int start-z)]
                          (doseq [x (range (- px 5) (+ px 6))
                                  y (range py (+ py 2))
                                  z (range (- pz 5) (+ pz 6))]
                            (when-let [hardness (block-manip/get-block-hardness
                                                block-manip/*block-manipulation*
                                                world-id x y z)]
                              (when (<= hardness 0.6)
                                (block-manip/break-block! block-manip/*block-manipulation*
                                                         player-id world-id x y z true))))))

                      ;; Grant experience
                      (when-let [state (ps/get-player-state player-id)]
                        (let [{:keys [data events]} (learning/add-skill-exp
                                                     (:ability-data state)
                                                     player-id
                                                     :groundshock
                                                     0.001
                                                     1.0)]
                          (ps/update-ability-data! player-id (constantly data))
                          (doseq [e events]
                            (ability-evt/fire-ability-event! e))))

                      (log/info "Groundshock: Affected" affected-count "blocks/entities")))))))

          ;; Invalid conditions
          (log/debug "Groundshock: Invalid conditions (charge:" charge-ticks ")"))))
    (catch Exception e
      (log/warn "Groundshock key-up failed:" (ex-message e)))))

(defn groundshock-on-key-abort
  "Clean up state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "Groundshock aborted")
    (catch Exception e
      (log/warn "Groundshock key-abort failed:" (ex-message e)))))
