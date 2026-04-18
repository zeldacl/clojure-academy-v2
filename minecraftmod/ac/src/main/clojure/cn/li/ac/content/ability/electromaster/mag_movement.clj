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
  - Grants experience based on distance traveled"
  (:require [clojure.string :as str]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal :refer [by-exp]]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.effect.motion]
            [cn.li.ac.ability.server.effect.state]
            [cn.li.ac.ability.server.effect.fx]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

(def ^:private accel 0.08)

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

(defn- is-metal-block? [block-id exp]
  (let [id      (normalize-id block-id)
        normal? (contains? normal-metal-blocks id)
        weak?   (contains? weak-metal-blocks id)
        metal?  (or normal? weak?)]
    (and (if (< (double exp) 0.6) metal? true)
         (or weak? metal?))))

(defn- is-metal-entity? [entity-type]
  (contains? metallic-entity-types (normalize-id entity-type)))

(defn- player-pos [player-id]
  (get (ps/get-player-state player-id)
       :position
       {:world-id "minecraft:overworld" :x 0.0 :y 64.0 :z 0.0}))

(defn- try-adjust [from to]
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
                       (double target-x) (double target-y) (double target-z)
                       4.0)
          matched (some #(when (= (:uuid %) target-entity-uuid) %) candidates)]
      (when matched
        (assoc skill-state
               :target-x (double (:x matched))
               :target-y (+ (double (:y matched)) 1.0)
               :target-z (double (:z matched)))))))

(defn- resolve-target [player-id exp]
  (when-let [look (when raycast/*raycast*
                    (raycast/get-player-look-vector raycast/*raycast* player-id))]
    (let [eye      (geom/eye-pos player-id)
          world-id (geom/world-id-of player-id)
          hit      (raycast/raycast-combined raycast/*raycast*
                                             world-id
                                             (:x eye) (:y eye) (:z eye)
                                             (double (:x look))
                                             (double (:y look))
                                             (double (:z look))
                                             25.0)]
      (case (:hit-type hit)
        :block
        (let [block-id (normalize-id (:block-id hit))]
          (when (is-metal-block? block-id exp)
            {:target-kind     :block
             :target-world-id world-id
             :target-x        (+ 0.5 (double (:x hit)))
             :target-y        (+ 0.5 (double (:y hit)))
             :target-z        (+ 0.5 (double (:z hit)))
             :target-block-id block-id}))
        :entity
        (let [entity-type (normalize-id (:type hit))]
          (when (is-metal-entity? entity-type)
            {:target-kind        :entity
             :target-world-id    world-id
             :target-entity-uuid (:uuid hit)
             :target-entity-type entity-type
             :target-x           (double (:x hit))
             :target-y           (+ (double (:y hit)) 1.0)
             :target-z           (double (:z hit))}))
        nil))))

;; ---------------------------------------------------------------------------
;; Cost fn (tick CP is conditional on having a target)
;; ---------------------------------------------------------------------------

(defn- tick-cp-cost [{:keys [ctx-id exp]}]
  (if-let [ctx (ctx/get-context ctx-id)]
    (if (get-in ctx [:skill-state :has-target])
      (bal/lerp 15.0 8.0 (double (or exp 0.0)))
      0.0)
    0.0))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn- finish-movement! [player-id skill-state evt]
  (when skill-state
    (let [{:keys [x y z]} (player-pos player-id)
          traveled        (geom/vdist {:x x :y y :z z}
                                      {:x (:start-x skill-state)
                                       :y (:start-y skill-state)
                                       :z (:start-z skill-state)})]
      (skill-effects/add-skill-exp! player-id :mag-movement
                                    (max 0.005 (* 0.0011 traveled))))
    (effect/run-op! evt [:reset-fall-damage nil])
    (effect/run-op! evt [:fx {:topic :mag-movement/fx-end
                              :payload {:mode :end}}])))

(defn- on-down! [{:keys [player-id ctx-id cost-ok? exp] :as evt}]
  (if-not cost-ok?
    (do (ctx/update-context! ctx-id assoc :skill-state {:has-target false})
        (log/debug "MagMovement: insufficient resource for activation"))
    (let [state-pos (player-pos player-id)]
      (if-let [{:keys [target-x target-y target-z] :as target-state}
               (resolve-target player-id (double (or exp 0.0)))]
        (let [velocity-now (when player-motion/*player-motion*
                             (player-motion/get-velocity player-motion/*player-motion* player-id))]
          (ctx/update-context! ctx-id assoc :skill-state
                               (merge target-state
                                      {:has-target true
                                       :movement-ticks 0
                                       :overload-floor (bal/lerp 60.0 30.0 (double (or exp 0.0)))
                                       :start-x        (double (:x state-pos))
                                       :start-y        (double (:y state-pos))
                                       :start-z        (double (:z state-pos))
                                       :motion-x       (double (or (:x velocity-now) 0.0))
                                       :motion-y       (double (or (:y velocity-now) 0.0))
                                       :motion-z       (double (or (:z velocity-now) 0.0))}))
          (effect/run-op! evt [:fx {:topic :mag-movement/fx-start
                                    :payload {:mode :start}}])
          (effect/run-op! evt [:fx {:topic   :mag-movement/fx-update
                                    :payload {:mode   :update
                                              :target {:x (double target-x)
                                                       :y (double target-y)
                                                       :z (double target-z)}}}])
          (log/debug "MagMovement started" (:target-kind target-state)))
        (do (ctx/update-context! ctx-id assoc :skill-state {:has-target false})
            (log/debug "MagMovement: no valid magnetic target"))))))

(defn- on-tick! [{:keys [player-id ctx-id cost-ok?] :as evt}]
  (when-let [ctx (ctx/get-context ctx-id)]
    (let [skill-state (:skill-state ctx)
          has-target  (:has-target skill-state)]
      (when has-target
        (let [updated-state (if (= :entity (:target-kind skill-state))
                              (update-entity-target skill-state)
                              skill-state)]
          (if-not updated-state
            (do (finish-movement! player-id skill-state evt)
                (ctx/update-context! ctx-id assoc-in [:skill-state :has-target] false))
            (let [movement-ticks (inc (int (:movement-ticks updated-state)))]
              (ctx/update-context! ctx-id assoc :skill-state
                                   (assoc updated-state :movement-ticks movement-ticks))
              (effect/run-op! evt [:overload-floor {:floor (:overload-floor updated-state)}])
              (if-not cost-ok?
                (do (finish-movement! player-id updated-state evt)
                    (ctx/update-context! ctx-id assoc-in [:skill-state :has-target] false))
                (let [p         (player-pos player-id)
                      tx        (double (:target-x updated-state))
                      ty        (double (:target-y updated-state))
                      tz        (double (:target-z updated-state))
                      dx        (- tx (double (:x p)))
                      dy        (- ty (double (:y p)))
                      dz        (- tz (double (:z p)))
                      dist      (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
                      scale     (if (> dist 1.0e-6) dist 1.0)
                      desired-x (/ dx scale)
                      desired-y (/ dy scale)
                      desired-z (/ dz scale)
                      player-vel (when player-motion/*player-motion*
                                   (player-motion/get-velocity player-motion/*player-motion* player-id))
                      cur-vx    (double (or (:x player-vel) 0.0))
                      cur-vy    (double (or (:y player-vel) 0.0))
                      cur-vz    (double (or (:z player-vel) 0.0))
                      mx        (double (:motion-x updated-state))
                      my        (double (:motion-y updated-state))
                      mz        (double (:motion-z updated-state))
                      speed-sq-last (+ (* cur-vx cur-vx) (* cur-vy cur-vy) (* cur-vz cur-vz))
                      speed-sq-m    (+ (* mx mx) (* my my) (* mz mz))
                      [base-x base-y base-z] (if (> (Math/abs (- speed-sq-m speed-sq-last)) 0.5)
                                               [cur-vx cur-vy cur-vz]
                                               [mx my mz])
                      next-x    (try-adjust base-x desired-x)
                      next-y    (try-adjust base-y desired-y)
                      next-z    (try-adjust base-z desired-z)]
                  (when player-motion/*player-motion*
                    (player-motion/set-velocity! player-motion/*player-motion*
                                                 player-id next-x next-y next-z))
                  (ctx/update-context! ctx-id assoc-in [:skill-state :motion-x] next-x)
                  (ctx/update-context! ctx-id assoc-in [:skill-state :motion-y] next-y)
                  (ctx/update-context! ctx-id assoc-in [:skill-state :motion-z] next-z)
                  (effect/run-op! evt [:fx {:topic   :mag-movement/fx-update
                                            :payload {:mode   :update
                                                      :target {:x tx :y ty :z tz}}}])
                  (when (zero? (mod movement-ticks 10))
                    (log/debug "MagMovement: moving for" (/ movement-ticks 20.0) "seconds")))))))))))

(defn- on-up! [{:keys [player-id ctx-id] :as evt}]
  (when-let [ctx (ctx/get-context ctx-id)]
    (let [skill-state (:skill-state ctx)
          has-target  (:has-target skill-state)]
      (when has-target
        (finish-movement! player-id skill-state evt)
        (ctx/update-context! ctx-id assoc-in [:skill-state :has-target] false)
        (log/debug "MagMovement completed: ticks" (:movement-ticks skill-state))))))

(defn- on-abort! [{:keys [player-id ctx-id] :as evt}]
  (when-let [ctx (ctx/get-context ctx-id)]
    (finish-movement! player-id (:skill-state ctx) evt))
  (ctx/update-context! ctx-id dissoc :skill-state)
  (log/debug "MagMovement aborted"))



(defskill! mag-movement
  :id              :mag-movement
  :category-id     :electromaster
  :name-key        "ability.skill.electromaster.mag_movement"
  :description-key "ability.skill.electromaster.mag_movement.desc"
  :icon            "textures/abilities/electromaster/skills/mag_movement.png"
  :ui-position     [137 35]
  :level           3
  :controllable?   true
  :ctrl-id         :mag-movement
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks  60
  :pattern         :charge-window
  :cooldown        {:mode :manual}
  :cost {:down {:overload   (by-exp 60.0 30.0)
                :creative?  (fn [{:keys [player]}]
                              (boolean (and player (entity/player-creative? player))))}
         :tick {:cp         tick-cp-cost
                :creative?  (fn [{:keys [player]}]
                              (boolean (and player (entity/player-creative? player))))}}
  :actions {:down!  on-down!
            :tick!  on-tick!
            :up!    on-up!
            :abort! on-abort!}
  :prerequisites [{:skill-id :arc-gen         :min-exp 1.0}
                  {:skill-id :current-charging :min-exp 0.7}])
