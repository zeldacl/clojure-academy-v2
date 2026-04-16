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
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(def ^:private min-charge-ticks 5)
(def ^:private ground-break-prob 0.3)

(defn- lerp [a b t]
  (+ (double a) (* (- (double b) (double a)) (double t))))

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (double x))))

(defn- v* [v scalar]
  {:x (* (double (:x v)) (double scalar))
   :y (* (double (:y v)) (double scalar))
   :z (* (double (:z v)) (double scalar))})

(defn- vlen [v]
  (Math/sqrt (+ (* (:x v) (:x v))
                (* (:y v) (:y v))
                (* (:z v) (:z v)))))

(defn- normalize [v]
  (let [length (max 1.0e-6 (vlen v))]
    (v* v (/ 1.0 length))))

(defn- horizontal-look [player-id]
  (when-let [look-vec (and raycast/*raycast*
                           (raycast/get-player-look-vector raycast/*raycast* player-id))]
    (let [flat {:x (double (:x look-vec))
                :y 0.0
                :z (double (:z look-vec))}
          length (vlen flat)]
      (when (> length 1.0e-6)
        (normalize flat)))))

(defn- perpendicular [flat-dir]
  {:x (- (double (:z flat-dir)))
   :y 0.0
   :z (double (:x flat-dir))})

(defn- cp-cost [exp]
  (lerp 80.0 150.0 (clamp01 exp)))

(defn- overload-cost [exp]
  (lerp 15.0 10.0 (clamp01 exp)))

(defn- init-energy [exp]
  (lerp 60.0 120.0 (clamp01 exp)))

(defn- max-iterations [exp]
  (int (lerp 10.0 25.0 (clamp01 exp))))

(defn- damage-value [exp]
  (lerp 4.0 6.0 (clamp01 exp)))

(defn- drop-rate [exp]
  (lerp 0.3 1.0 (clamp01 exp)))

(defn- cooldown-ticks [exp]
  (int (lerp 80.0 40.0 (clamp01 exp))))

(defn- launch-y-speed [exp]
  (* (+ 0.6 (* (rand) 0.3))
     (lerp 0.8 1.3 (clamp01 exp))))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :groundshock :exp] 0.0)))

(defn- add-exp! [player-id amount]
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data events]} (learning/add-skill-exp
                                 (:ability-data state)
                                 player-id
                                 :groundshock
                                 (double amount)
                                 1.0)]
      (ps/update-ability-data! player-id (constantly data))
      (doseq [event events]
        (ability-evt/fire-ability-event! event)))))

(defn groundshock-cost-up-cp
  [{:keys [player-id]}]
  (cp-cost (clamp01 (get-skill-exp player-id))))

(defn groundshock-cost-up-overload
  [{:keys [player-id]}]
  (overload-cost (clamp01 (get-skill-exp player-id))))

(defn- apply-cooldown! [player-id exp]
  (ps/update-cooldown-data! player-id cd/set-main-cooldown :groundshock (max 1 (cooldown-ticks exp))))

(defn- get-player-position
  "Get player position from teleportation protocol."
  [player-id]
  (or (when-let [teleportation (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
        (when-let [tp-impl @teleportation]
          ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) tp-impl player-id)))
      (get (ps/get-player-state player-id)
           :position
           {:world-id "minecraft:overworld"
            :x 0.0
            :y 64.0
            :z 0.0})))

(defn- send-fx-start! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :groundshock/fx-start {:mode :start}))

(defn- send-fx-update! [ctx-id charge-ticks]
  (ctx/ctx-send-to-client! ctx-id :groundshock/fx-update
                           {:mode :update
                            :charge-ticks (long (max 0 charge-ticks))}))

(defn- send-fx-perform! [ctx-id affected-blocks broken-blocks]
  (ctx/ctx-send-to-client! ctx-id :groundshock/fx-perform
                           {:mode :perform
                            :affected-blocks affected-blocks
                            :broken-blocks broken-blocks}))

(defn- send-fx-end! [ctx-id performed?]
  (ctx/ctx-send-to-client! ctx-id :groundshock/fx-end
                           {:mode :end
                            :performed? (boolean performed?)}))

(defn- entity-overlaps-shock-box?
  [entity bx by bz]
  (let [half-width (/ (double (or (:width entity) 0.6)) 2.0)
        min-ex (- (double (or (:x entity) 0.0)) half-width)
        max-ex (+ (double (or (:x entity) 0.0)) half-width)
        min-ey (double (or (:y entity) 0.0))
        max-ey (+ min-ey (double (or (:height entity) 1.8)))
        min-ez (- (double (or (:z entity) 0.0)) half-width)
        max-ez (+ (double (or (:z entity) 0.0)) half-width)]
    (and (< min-ex (+ (double bx) 1.4))
         (> max-ex (- (double bx) 0.2))
         (< min-ey (+ (double by) 2.2))
         (> max-ey (- (double by) 0.2))
         (< min-ez (+ (double bz) 1.4))
         (> max-ez (- (double bz) 0.2)))))

(defn- break-with-force!
  [player-id world-id x y z drop? energy* block-drop-rate broken-blocks*]
  (when (and block-manip/*block-manipulation*
             (block-manip/can-break-block? block-manip/*block-manipulation* player-id world-id x y z))
    (let [hardness (block-manip/get-block-hardness block-manip/*block-manipulation* world-id x y z)
          block-id (block-manip/get-block block-manip/*block-manipulation* world-id x y z)]
      (when (and block-id
                 (number? hardness)
                 (>= (double hardness) 0.0)
                 (>= @energy* (double hardness))
                 (not (block-manip/farmland-block? block-manip/*block-manipulation* world-id x y z))
                 (not (block-manip/liquid-block? block-manip/*block-manipulation* world-id x y z)))
        (swap! energy* - (double hardness))
        (when (block-manip/break-block! block-manip/*block-manipulation*
                                        player-id
                                        world-id
                                        x y z
                                        (and drop? (< (rand) block-drop-rate)))
          (swap! broken-blocks* conj [x y z])
          (when world-effects/*world-effects*
            (world-effects/play-sound! world-effects/*world-effects*
                                       world-id
                                       (+ (double x) 0.5)
                                       (+ (double y) 0.5)
                                       (+ (double z) 0.5)
                                       "minecraft:block.anvil.destroy"
                                       :ambient
                                       0.5
                                       1.0))
          true)))))

(defn groundshock-on-key-down
  "Initialize charge state."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id assoc :skill-state
                         {:charge-ticks 0
                          :performed? false})
    (send-fx-start! ctx-id)
    (log/debug "Groundshock: Charge started")
    (catch Exception e
      (log/warn "Groundshock key-down failed:" (ex-message e)))))

(defn groundshock-on-key-tick
  "Update charge progress."
  [{:keys [ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (long (or (:charge-ticks skill-state) 0))
            next-charge (inc charge-ticks)]
        (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] next-charge)
        (send-fx-update! ctx-id next-charge)))
    (catch Exception e
      (log/warn "Groundshock key-tick failed:" (ex-message e)))))

(defn- affect-entities!
  [player-id world-id bx by bz damage y-speed affected-entities*]
  (when world-effects/*world-effects*
    (doseq [entity (world-effects/find-entities-in-radius world-effects/*world-effects*
                                                          world-id
                                                          (+ (double bx) 0.5)
                                                          (+ (double by) 1.0)
                                                          (+ (double bz) 0.5)
                                                          2.0)]
      (let [entity-id (:uuid entity)]
        (when (and entity-id
                   (not= entity-id player-id)
                   (not (contains? @affected-entities* entity-id))
                   (entity-overlaps-shock-box? entity bx by bz))
          (when entity-damage/*entity-damage*
            (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                world-id
                                                entity-id
                                                damage
                                                :generic))
          (when entity-motion/*entity-motion*
            (entity-motion/add-velocity! entity-motion/*entity-motion*
                                         world-id
                                         entity-id
                                         0.0
                                         y-speed
                                         0.0))
          (swap! affected-entities* conj entity-id)
          (add-exp! player-id 0.002))))))

(defn- propagation-positions [perp]
  [[{:x 0.0 :y 0.0 :z 0.0} 1.0]
   [perp 0.7]
   [(v* perp -1.0) 0.7]
   [(v* perp 2.0) 0.3]
   [(v* perp -2.0) 0.3]])

(defn- finalize-affected-blocks [world-id positions]
  (mapv (fn [[x y z]]
          {:x x
           :y y
           :z z
           :block-id (or (and block-manip/*block-manipulation*
                              (block-manip/get-block block-manip/*block-manipulation* world-id x y z))
                         "minecraft:stone")})
        positions))

(defn- finalize-broken-blocks [positions]
  (mapv (fn [[x y z]]
          {:x x
           :y y
           :z z})
        positions))

(defn- propagate-shockwave!
  "Propagate shockwave along ground in look direction."
  [player-id world-id start-x start-y start-z flat-dir exp]
  (let [energy* (atom (init-energy exp))
        damage (damage-value exp)
        y-speed (launch-y-speed exp)
        block-drop-rate (drop-rate exp)
        max-iter (max-iterations exp)
        affected-blocks* (atom #{})
        affected-entities* (atom #{})
        broken-blocks* (atom #{})
        perp (perpendicular flat-dir)]
    (loop [iter 0
           x (Math/floor (double start-x))
           z (Math/floor (double start-z))]
      (if (or (<= @energy* 0.0) (>= iter max-iter))
        {:affected-blocks (finalize-affected-blocks world-id @affected-blocks*)
         :affected-entities @affected-entities*
         :broken-blocks (finalize-broken-blocks @broken-blocks*)}
        (let [block-x (int (Math/floor x))
              block-y (int (Math/floor start-y))
              block-z (int (Math/floor z))]
          (doseq [[delta prob] (propagation-positions perp)]
            (when (< (rand) prob)
              (let [bx (int (Math/floor (+ x (:x delta))))
                    by block-y
                    bz (int (Math/floor (+ z (:z delta))))
                    pos-key [bx by bz]]
                (when-not (contains? @affected-blocks* pos-key)
                  (when-let [block-id (and block-manip/*block-manipulation*
                                           (block-manip/get-block block-manip/*block-manipulation*
                                                                  world-id bx by bz))]
                    (swap! affected-blocks* conj pos-key)
                    (case block-id
                      "minecraft:stone"
                      (do
                        (block-manip/set-block! block-manip/*block-manipulation*
                                                world-id bx by bz "minecraft:cobblestone")
                        (swap! energy* - 0.4))

                      "minecraft:grass_block"
                      (do
                        (block-manip/set-block! block-manip/*block-manipulation*
                                                world-id bx by bz "minecraft:dirt")
                        (swap! energy* - 0.2))

                      "minecraft:farmland"
                      (swap! energy* - 0.1)

                      (swap! energy* - 0.5))

                    (when (< (rand) ground-break-prob)
                      (break-with-force! player-id world-id block-x block-y block-z false energy* block-drop-rate broken-blocks*))

                    (let [before-entities (count @affected-entities*)]
                      (affect-entities! player-id world-id bx by bz damage y-speed affected-entities*)
                      (swap! energy* - (double (- (count @affected-entities*) before-entities))))))))

            (doseq [dy (range 1 4)]
              (break-with-force! player-id world-id block-x (+ block-y dy) block-z false energy* block-drop-rate broken-blocks*)))

          (recur (inc iter)
                 (+ x (:x flat-dir))
                 (+ z (:z flat-dir))))))))

(defn- break-mastery-ring!
  [player-id world-id player-pos exp broken-blocks*]
  (when (and (= 1.0 (clamp01 exp))
             block-manip/*block-manipulation*)
    (let [energy* (atom Double/MAX_VALUE)
          x0 (int (double (:x player-pos)))
          y0 (int (double (:y player-pos)))
          z0 (int (double (:z player-pos)))]
      (doseq [x (range (- x0 5) (+ x0 5))
              y (range (- y0 1) (+ y0 1))
              z (range (- z0 5) (+ z0 5))]
        (when-let [hardness (block-manip/get-block-hardness block-manip/*block-manipulation* world-id x y z)]
          (when (and (number? hardness)
                     (<= (double hardness) 0.6))
            (break-with-force! player-id world-id x y z true energy* 1.0 broken-blocks*)))))))

(defn groundshock-on-key-up
  "Perform the ground slam."
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (long (or (:charge-ticks skill-state) 0))
            exp (clamp01 (get-skill-exp player-id))]

        ;; Check if charge is valid (5+ ticks) and player is on ground
        (if (and (>= charge-ticks min-charge-ticks)
                 player-motion/*player-motion*
                 (player-motion/is-on-ground? player-motion/*player-motion* player-id))
          (if-not cost-ok?
            (do
              (send-fx-end! ctx-id false)
              (log/debug "Groundshock perform failed: insufficient resource"))
            (when-let [pos (get-player-position player-id)]
              (if-let [flat-dir (horizontal-look player-id)]
                (let [world-id (or (:world-id pos) "minecraft:overworld")
                      start-x (double (:x pos))
                      start-y (dec (double (:y pos)))
                      start-z (double (:z pos))
                      result (propagate-shockwave! player-id world-id
                                                   start-x start-y start-z
                                                   flat-dir exp)
                      broken-blocks* (atom (into #{} (map (juxt :x :y :z) (:broken-blocks result))))
                      affected-count (+ (count (:affected-blocks result))
                                        (count (:affected-entities result)))]
                  (break-mastery-ring! player-id world-id pos exp broken-blocks*)
                  (apply-cooldown! player-id exp)
                  (add-exp! player-id 0.001)
                  (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] true)
                  (send-fx-perform! ctx-id
                                    (:affected-blocks result)
                                    (finalize-broken-blocks @broken-blocks*))
                  (log/info "Groundshock: Affected" affected-count "blocks/entities"))
                (do
                  (send-fx-end! ctx-id false)
                  (log/debug "Groundshock: Missing horizontal look vector")))))

          ;; Invalid conditions
          (do
            (send-fx-end! ctx-id false)
            (log/debug "Groundshock: Invalid conditions (charge:" charge-ticks ")")))))
    (catch Exception e
      (log/warn "Groundshock key-up failed:" (ex-message e)))))

(defn groundshock-on-key-abort
  "Clean up state on abort."
  [{:keys [ctx-id]}]
  (try
    (send-fx-end! ctx-id false)
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "Groundshock aborted")
    (catch Exception e
      (log/warn "Groundshock key-abort failed:" (ex-message e)))))
