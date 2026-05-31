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
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.content.ability.fx-helpers :as fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(def ^:private groundshock-skill-id :groundshock)

(defn- exp01 [exp]
  (max 0.0 (min 1.0 (double (or exp 0.0)))))

(defn- cfg-double [field-id]
  (skill-config/tunable-double groundshock-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int groundshock-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double groundshock-skill-id field-id (exp01 exp)))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int groundshock-skill-id field-id (exp01 exp)))

(defn- cfg-boolean [field-id]
  (skill-config/tunable-boolean groundshock-skill-id field-id))

(defn- horizontal-look [player-id]
  (when-let [look-vec (and raycast/*raycast*
                           (raycast/get-player-look-vector raycast/*raycast* player-id))]
    (let [flat {:x (double (:x look-vec))
                :y 0.0
                :z (double (:z look-vec))}
          length (geom/vlen flat)]
      (when (> length 1.0e-6)
        (geom/vnorm flat)))))

(defn- horizontal-look-with-fallback [player-id]
  (or (horizontal-look player-id)
      (when (cfg-boolean :targeting.horizontal-look-fallback)
        {:x 0.0 :y 0.0 :z 1.0})))

(defn- perpendicular [flat-dir]
  {:x (- (double (:z flat-dir)))
   :y 0.0
   :z (double (:x flat-dir))})

(defn- cp-cost [exp]
  (cfg-lerp :cost.up.cp exp))

(defn- overload-cost [exp]
  (cfg-lerp :cost.up.overload exp))

(defn- init-energy [exp]
  (cfg-lerp :effect.init-energy exp))

(defn- propagation-energy-cost [block-id]
  (case block-id
    "minecraft:stone" (cfg-double :effect.energy-cost.stone)
    "minecraft:grass_block" (cfg-double :effect.energy-cost.grass-block)
    "minecraft:farmland" (cfg-double :effect.energy-cost.farmland)
    (cfg-double :effect.energy-cost.default-block)))

(defn- max-iterations [exp]
  (cfg-lerp-int :effect.max-iterations exp))

(defn- damage-value [exp]
  (cfg-lerp :combat.damage exp))

(defn- drop-rate [exp]
  (cfg-lerp :breaking.drop-rate exp))

(defn- cooldown-ticks [exp]
  (cfg-lerp-int :cooldown.ticks exp))

(defn- launch-y-speed [exp]
  (* (+ (cfg-double :movement.launch-random-base)
        (* (rand) (cfg-double :movement.launch-random-span)))
     (cfg-lerp :movement.launch-scale exp)))

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id :groundshock))

(defn- add-exp! [player-id amount]
  (skill-effects/add-skill-exp! player-id :groundshock amount))

(defn groundshock-cost-up-cp
  [{:keys [player-id]}]
  (cp-cost (exp01 (skill-exp player-id))))

(defn groundshock-cost-up-overload
  [{:keys [player-id]}]
  (overload-cost (exp01 (skill-exp player-id))))

(defn- apply-cooldown! [player-id exp]
  (skill-effects/set-main-cooldown! player-id :groundshock (cooldown-ticks exp)))

(defn- get-player-position
  "Get player position from teleportation protocol."
  [player-id]
  (when teleportation/*teleportation*
    (teleportation/get-player-position teleportation/*teleportation* player-id)))

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
    (fx/send-start! ctx-id :groundshock/fx-start)
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
        (fx/send-update! ctx-id :groundshock/fx-update
             {:charge-ticks (long (max 0 next-charge))})))
    (catch Exception e
      (log/warn "Groundshock key-tick failed:" (ex-message e)))))

(defn- affect-entities!
  [player-id world-id bx by bz damage y-speed candidate-entities affected-entities*]
  (doseq [entity candidate-entities]
    (let [entity-id (:uuid entity)]
      (when (and entity-id
                 (not= entity-id player-id)
                 (true? (:living? entity))
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
        (add-exp! player-id (cfg-double :progression.exp-entity))))))

(defn- propagation-positions [perp]
  [[{:x 0.0 :y 0.0 :z 0.0} 1.0]
   [perp 0.7]
   [(geom/v* perp -1.0) 0.7]
   [(geom/v* perp 2.0) 0.3]
   [(geom/v* perp -2.0) 0.3]])

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
        entity-search-radius (cfg-double :combat.entity-search-radius)
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
              block-z (int (Math/floor z))
              candidate-entities (when world-effects/*world-effects*
                                   (world-effects/find-entities-in-aabb world-effects/*world-effects*
                                     world-id
                                     (- x (+ entity-search-radius 3.0))
                                     (- block-y 2.0)
                                     (- z (+ entity-search-radius 3.0))
                                     (+ x (+ entity-search-radius 3.0))
                                     (+ block-y 4.0)
                                     (+ z (+ entity-search-radius 3.0))))]
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
                        (swap! energy* - (propagation-energy-cost block-id)))

                      "minecraft:grass_block"
                      (do
                        (block-manip/set-block! block-manip/*block-manipulation*
                                                world-id bx by bz "minecraft:dirt")
                        (swap! energy* - (propagation-energy-cost block-id)))

                      "minecraft:farmland"
                      (swap! energy* - (propagation-energy-cost block-id))

                      (swap! energy* - (propagation-energy-cost block-id)))

                    (when (< (rand) (cfg-double :breaking.ground-break-probability))
                      (break-with-force! player-id world-id block-x block-y block-z false energy* block-drop-rate broken-blocks*))

                    (let [before-entities (count @affected-entities*)]
                      (affect-entities! player-id world-id bx by bz damage y-speed candidate-entities affected-entities*)
                      (swap! energy* - (double (- (count @affected-entities*) before-entities))))))))

            (doseq [dy (range 1 4)]
              (break-with-force! player-id world-id block-x (+ block-y dy) block-z false energy* block-drop-rate broken-blocks*)))

          (recur (inc iter)
                 (+ x (:x flat-dir))
                 (+ z (:z flat-dir))))))))

(defn- break-mastery-ring!
  [player-id world-id player-pos exp broken-blocks*]
  (when (and (>= (exp01 exp) (cfg-double :breaking.mastery-exp-threshold))
             block-manip/*block-manipulation*)
    (let [energy* (atom Double/MAX_VALUE)
          x0 (int (double (:x player-pos)))
          y0 (int (double (:y player-pos)))
          z0 (int (double (:z player-pos)))]
      (doseq [x (range (- x0 (cfg-int :breaking.mastery-radius)) (+ x0 (cfg-int :breaking.mastery-radius)))
              y (range (- y0 1) (+ y0 1))
          z (range (- z0 (cfg-int :breaking.mastery-radius)) (+ z0 (cfg-int :breaking.mastery-radius)))]
        (when-let [hardness (block-manip/get-block-hardness block-manip/*block-manipulation* world-id x y z)]
          (when (and (number? hardness)
             (<= (double hardness) (cfg-double :breaking.mastery-hardness-cap)))
            (break-with-force! player-id world-id x y z true energy* 1.0 broken-blocks*)))))))

(defn groundshock-on-key-up
  "Perform the ground slam."
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (long (or (:charge-ticks skill-state) 0))
            exp (exp01 (skill-exp player-id))]

        ;; Check if charge is valid (5+ ticks) and player is on ground
          (if (and (>= charge-ticks (cfg-int :charge.min-ticks))
                 player-motion/*player-motion*
                 (player-motion/is-on-ground? player-motion/*player-motion* player-id))
          (if-not cost-ok?
            (do
              (fx/send-end! ctx-id :groundshock/fx-end {:performed? false})
              (log/debug "Groundshock perform failed: insufficient resource"))
            (if-let [pos (get-player-position player-id)]
              (if-let [flat-dir (horizontal-look-with-fallback player-id)]
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
                  (add-exp! player-id (cfg-double :progression.exp-use))
                  (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] true)
                  (fx/send-perform! ctx-id :groundshock/fx-perform
                                   {:affected-blocks (:affected-blocks result)
                                    :broken-blocks (finalize-broken-blocks @broken-blocks*)})
                  (log/info "Groundshock: Affected" affected-count "blocks/entities"))
                (do
                  (fx/send-end! ctx-id :groundshock/fx-end {:performed? false})
                  (log/debug "Groundshock: Missing horizontal look vector")))
              (do
                (fx/send-end! ctx-id :groundshock/fx-end {:performed? false})
                (log/debug "Groundshock: Missing player position"))))

          ;; Invalid conditions
          (do
            (fx/send-end! ctx-id :groundshock/fx-end {:performed? false})
            (log/debug "Groundshock: Invalid conditions (charge:" charge-ticks ")")))))
    (catch Exception e
      (log/warn "Groundshock key-up failed:" (ex-message e)))))

(defn groundshock-on-key-abort
  "Clean up state on abort."
  [{:keys [ctx-id]}]
  (try
    (fx/send-end! ctx-id :groundshock/fx-end {:performed? false})
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "Groundshock aborted")
    (catch Exception e
      (log/warn "Groundshock key-abort failed:" (ex-message e)))))

(defskill groundshock
  :id :groundshock
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.groundshock"
  :description-key "ability.skill.vecmanip.groundshock.desc"
  :icon "textures/abilities/vecmanip/skills/ground_shock.png"
  :ui-position [64 85]
  :level 1
  :controllable? false
  :ctrl-id :groundshock
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 80
  :pattern :release-cast
  :cooldown {:mode :manual}
  :cost {:up {:cp groundshock-cost-up-cp
              :overload groundshock-cost-up-overload}}
  :actions {:down! groundshock-on-key-down
            :tick! groundshock-on-key-tick
            :up! groundshock-on-key-up
            :abort! groundshock-on-key-abort}
  :prerequisites [{:skill-id :directed-shock :min-exp 0.0}])
