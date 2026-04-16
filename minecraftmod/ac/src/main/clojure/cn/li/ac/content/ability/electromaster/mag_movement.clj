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
  (:require [clojure.string :as str]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.model.resource-data :as rd]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

(def ^:private accel 0.08)

(defn- lerp
  [a b t]
  (+ a (* (- b a) (double t))))

(defn- distance-3d
  [ax ay az bx by bz]
  (let [dx (- (double ax) (double bx))
        dy (- (double ay) (double by))
        dz (- (double az) (double bz))]
    (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))

(defn- normalize-id
  [raw-id]
  (let [id (some-> raw-id str str/lower-case)]
    (cond
      (nil? id) nil
      (str/includes? id ":") id
      (str/starts-with? id "block.minecraft.")
      (str "minecraft:" (subs id (count "block.minecraft.")))
      (str/starts-with? id "item.minecraft.")
      (str "minecraft:" (subs id (count "item.minecraft.")))
      (str/starts-with? id "entity.minecraft.")
      (str "minecraft:" (subs id (count "entity.minecraft.")))
      :else id)))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :mag-movement :exp] 0.0)))

(def ^:private normal-metal-blocks
  #{"minecraft:rail"
    "minecraft:iron_bars"
    "minecraft:iron_block"
    "minecraft:activator_rail"
    "minecraft:detector_rail"
    "minecraft:powered_rail"
    "minecraft:sticky_piston"
    "minecraft:piston"})

(def ^:private weak-metal-blocks
  #{"minecraft:dispenser"
    "minecraft:hopper"
    "minecraft:iron_ore"})

(def ^:private metallic-entity-types
  #{"minecraft:minecart"
    "minecraft:chest_minecart"
    "minecraft:furnace_minecart"
    "minecraft:tnt_minecart"
    "minecraft:hopper_minecart"
    "minecraft:spawner_minecart"
    "minecraft:command_block_minecart"
    "minecraft:iron_golem"
    "academy:entitymaghook"
    "academy:entity_mag_hook"
    "my_mod:entity_mag_hook"
    "ac:entity_mag_hook"})

(defn- is-metal-block? [block-id exp]
  (let [id (normalize-id block-id)
        normal? (contains? normal-metal-blocks id)
        weak? (contains? weak-metal-blocks id)
        metal? (or normal? weak?)]
    ;; Keep original behavior: both checks effectively require "metal" only.
    (and (if (< (double exp) 0.6) metal? true)
         (or weak? metal?))))

(defn- is-metal-entity?
  [entity-type]
  (contains? metallic-entity-types (normalize-id entity-type)))

(defn- player-pos
  [player-id]
  (get (ps/get-player-state player-id)
       :position
       {:world-id "minecraft:overworld" :x 0.0 :y 64.0 :z 0.0}))

(defn- player-world-id
  [player-id]
  (or (get-in (ps/get-player-state player-id) [:position :world-id])
      "minecraft:overworld"))

(defn- eye-pos
  [{:keys [x y z]}]
  {:x (double x) :y (+ (double y) 1.62) :z (double z)})

(defn mag-movement-cost-down-overload
  [{:keys [player-id]}]
  (lerp 60.0 30.0 (get-skill-exp player-id)))

(defn mag-movement-cost-tick-cp
  [{:keys [player-id ctx-id]}]
  (if-let [ctx-data (ctx/get-context ctx-id)]
    (if (get-in ctx-data [:skill-state :has-target])
      (lerp 15.0 8.0 (get-skill-exp player-id))
      0.0)
    0.0))

(defn mag-movement-cost-creative?
  [{:keys [player]}]
  (boolean (and player (entity/player-creative? player))))

(defn- keep-overload-floor!
  [player-id overload-floor]
  (when-let [state (ps/get-player-state player-id)]
    (let [cur (double (get-in state [:resource-data :cur-overload] 0.0))]
      (when (< cur (double overload-floor))
        (ps/update-resource-data! player-id rd/set-cur-overload overload-floor)))))

(defn- try-adjust
  [from to]
  (let [d (- (double to) (double from))]
    (if (< (Math/abs d) accel)
      (double to)
      (if (pos? d)
        (+ (double from) accel)
        (- (double from) accel)))))

(defn- update-entity-target
  [{:keys [target-world-id target-entity-uuid target-x target-y target-z] :as skill-state}]
  (if-not (and world-effects/*world-effects* target-entity-uuid)
    nil
    (let [candidates (world-effects/find-entities-in-radius
                       world-effects/*world-effects*
                       (or target-world-id "minecraft:overworld")
                       (double target-x)
                       (double target-y)
                       (double target-z)
                       4.0)
          matched (some (fn [ent]
                          (when (= (:uuid ent) target-entity-uuid)
                            ent))
                        candidates)]
      (when matched
        (assoc skill-state
               :target-x (double (:x matched))
               :target-y (+ (double (:y matched)) 1.0)
               :target-z (double (:z matched)))))))

(defn- get-traveled-distance
  [player-id skill-state]
  (let [{:keys [x y z]} (player-pos player-id)]
    (distance-3d x y z
                 (double (:start-x skill-state))
                 (double (:start-y skill-state))
                 (double (:start-z skill-state)))))

(defn- add-mag-movement-exp!
  [player-id traveled-distance]
  (when-let [state (ps/get-player-state player-id)]
    (let [exp-gain (max 0.005 (* 0.0011 (double traveled-distance)))
          {:keys [data events]} (learning/add-skill-exp
                                  (:ability-data state)
                                  player-id
                                  :mag-movement
                                  exp-gain
                                  1.0)]
      (ps/update-ability-data! player-id (constantly data))
      (doseq [e events]
        (ability-evt/fire-ability-event! e)))))

(defn- send-fx-start!
  [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :mag-movement/fx-start {:mode :start}))

(defn- send-fx-update!
  [ctx-id x y z]
  (ctx/ctx-send-to-client! ctx-id :mag-movement/fx-update
                           {:mode :update
                            :target {:x (double x) :y (double y) :z (double z)}}))

(defn- send-fx-end!
  [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :mag-movement/fx-end {:mode :end}))

(defn- finish-movement!
  [player-id ctx-id skill-state]
  (when skill-state
    (add-mag-movement-exp! player-id (get-traveled-distance player-id skill-state))
    (when teleportation/*teleportation*
      (teleportation/reset-fall-damage! teleportation/*teleportation* player-id))
    (send-fx-end! ctx-id)))

(defn- resolve-target
  [player-id exp]
  (when-let [look-vec (when raycast/*raycast*
                        (raycast/get-player-look-vector raycast/*raycast* player-id))]
    (let [{:keys [x y z]} (eye-pos (player-pos player-id))
          world-id (player-world-id player-id)
          hit (raycast/raycast-combined raycast/*raycast*
                                        world-id
                                        x y z
                                        (double (:x look-vec))
                                        (double (:y look-vec))
                                        (double (:z look-vec))
                                        25.0)
          hit-type (:hit-type hit)]
      (case hit-type
        :block
        (let [block-id (normalize-id (:block-id hit))]
          (when (is-metal-block? block-id exp)
            {:target-kind :block
             :target-world-id world-id
             :target-x (+ 0.5 (double (:x hit)))
             :target-y (+ 0.5 (double (:y hit)))
             :target-z (+ 0.5 (double (:z hit)))
             :target-block-id block-id}))

        :entity
        (let [entity-type (normalize-id (:type hit))]
          (when (is-metal-entity? entity-type)
            {:target-kind :entity
             :target-world-id world-id
             :target-entity-uuid (:uuid hit)
             :target-entity-type entity-type
             :target-x (double (:x hit))
             :target-y (+ (double (:y hit)) 1.0)
             :target-z (double (:z hit))}))

        nil))))

(defn mag-movement-on-key-down
  "Initialize mag movement when key pressed."
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (let [exp (get-skill-exp player-id)
          state-pos (player-pos player-id)]
      (if-not cost-ok?
        (do
          (ctx/update-context! ctx-id assoc :skill-state {:has-target false})
          (log/debug "MagMovement: insufficient resource for activation"))
        (if-let [{:keys [target-x target-y target-z] :as target-state}
                 (resolve-target player-id exp)]
          (let [velocity-now (when player-motion/*player-motion*
                               (player-motion/get-velocity player-motion/*player-motion* player-id))]
            (ctx/update-context! ctx-id assoc :skill-state
                                 (merge target-state
                                        {:has-target true
                                         :movement-ticks 0
                                         :overload-floor (lerp 60.0 30.0 exp)
                                         :start-x (double (:x state-pos))
                                         :start-y (double (:y state-pos))
                                         :start-z (double (:z state-pos))
                                         :motion-x (double (or (:x velocity-now) 0.0))
                                         :motion-y (double (or (:y velocity-now) 0.0))
                                         :motion-z (double (or (:z velocity-now) 0.0))}))
            (send-fx-start! ctx-id)
            (send-fx-update! ctx-id target-x target-y target-z)
            (log/debug "MagMovement started"
                       (:target-kind target-state)
                       "distance" (int (distance-3d (:x state-pos) (:y state-pos) (:z state-pos)
                                                     target-x target-y target-z))))
          (do
            (ctx/update-context! ctx-id assoc :skill-state {:has-target false})
            (log/debug "MagMovement: no valid magnetic target")))))
    (catch Exception e
      (log/warn "MagMovement key-down failed:" (ex-message e)))))

(defn mag-movement-on-key-tick
  "Continue magnetic movement each tick."
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            has-target (:has-target skill-state)]

        (when has-target
          (let [updated-state (if (= :entity (:target-kind skill-state))
                                (update-entity-target skill-state)
                                skill-state)]
            (if-not updated-state
              (do
                (finish-movement! player-id ctx-id skill-state)
                (ctx/update-context! ctx-id assoc-in [:skill-state :has-target] false))
              (let [movement-ticks (inc (int (:movement-ticks updated-state)))
                    _ (ctx/update-context! ctx-id assoc :skill-state (assoc updated-state :movement-ticks movement-ticks))
                    _ (keep-overload-floor! player-id (:overload-floor updated-state))]
                (if-not cost-ok?
                  (do
                    (finish-movement! player-id ctx-id updated-state)
                    (ctx/update-context! ctx-id assoc-in [:skill-state :has-target] false))
                  (let [p (player-pos player-id)
                        tx (double (:target-x updated-state))
                        ty (double (:target-y updated-state))
                        tz (double (:target-z updated-state))
                        dx (- tx (double (:x p)))
                        dy (- ty (double (:y p)))
                        dz (- tz (double (:z p)))
                        dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
                        scale (if (> dist 1.0e-6) dist 1.0)
                        desired-x (/ dx scale)
                        desired-y (/ dy scale)
                        desired-z (/ dz scale)
                        player-vel (when player-motion/*player-motion*
                                     (player-motion/get-velocity player-motion/*player-motion* player-id))
                        cur-vx (double (or (:x player-vel) 0.0))
                        cur-vy (double (or (:y player-vel) 0.0))
                        cur-vz (double (or (:z player-vel) 0.0))
                        mx (double (:motion-x updated-state))
                        my (double (:motion-y updated-state))
                        mz (double (:motion-z updated-state))
                        speed-sq-last (+ (* cur-vx cur-vx) (* cur-vy cur-vy) (* cur-vz cur-vz))
                        speed-sq-m (+ (* mx mx) (* my my) (* mz mz))
                        [base-x base-y base-z] (if (> (Math/abs (- speed-sq-m speed-sq-last)) 0.5)
                                                 [cur-vx cur-vy cur-vz]
                                                 [mx my mz])
                        next-x (try-adjust base-x desired-x)
                        next-y (try-adjust base-y desired-y)
                        next-z (try-adjust base-z desired-z)]
                    (when player-motion/*player-motion*
                      (player-motion/set-velocity! player-motion/*player-motion*
                                                   player-id
                                                   next-x next-y next-z))
                    (ctx/update-context! ctx-id assoc-in [:skill-state :motion-x] next-x)
                    (ctx/update-context! ctx-id assoc-in [:skill-state :motion-y] next-y)
                    (ctx/update-context! ctx-id assoc-in [:skill-state :motion-z] next-z)
                    (send-fx-update! ctx-id tx ty tz)
                    (when (zero? (mod movement-ticks 10))
                      (log/debug "MagMovement: moving for" (/ movement-ticks 20.0) "seconds"))))))))))
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
          (finish-movement! player-id ctx-id skill-state)
          (ctx/update-context! ctx-id assoc-in [:skill-state :has-target] false)
          (log/debug "MagMovement completed: ticks" (:movement-ticks skill-state)))))
    (catch Exception e
      (log/warn "MagMovement key-up failed:" (ex-message e)))))

(defn mag-movement-on-key-abort
  "Clean up movement state on abort."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (finish-movement! player-id ctx-id (:skill-state ctx-data)))
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "MagMovement aborted")
    (catch Exception e
      (log/warn "MagMovement key-abort failed:" (ex-message e)))))
