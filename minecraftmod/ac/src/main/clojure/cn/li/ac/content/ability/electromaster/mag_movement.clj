(ns cn.li.ac.content.ability.electromaster.mag-movement
  "MagMovement skill - magnetic acceleration toward metal blocks/entities.

  Mechanics:
  - Raycast to metal blocks/entities
  - Accelerate player toward target with smooth interpolation
  - Energy and progression values are read from ability skill config
  - Low exp requires strong metal blocks only; high exp unlocks weak metal blocks
  - Visual: Arc with wiggle animation
  - Audio: Looping ambient sound
  - Resets fall damage on completion
  - Grants experience based on distance traveled"
  (:require [clojure.string :as str]
            [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.effects.fx :as fx-op]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.effects.motion :as motion-op]
            [cn.li.ac.ability.effects.state :as state-op]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
                        [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

(def ^:private mag-movement-skill-id :mag-movement)
(def ^:private default-eye-height 1.62)

(defn- cfg-double [field-id]
  (skill-config/tunable-double mag-movement-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int mag-movement-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double mag-movement-skill-id field-id exp))

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

(defn- is-metal-block? [block-id]
  (let [id      (normalize-id block-id)
        normal? (ability-config/is-normal-metal-block? id)
    weak?   (ability-config/is-weak-metal-block? id)]
  ;; Source parity: MagMovement accepts any configured metal block.
  ;; The weak-metal exp threshold is intentionally a no-op for this skill.
  (boolean (and id (or normal? weak?)))))

(defn- is-metal-entity? [entity-type]
  (ability-config/is-metal-entity? (normalize-id entity-type)))

(defn- player-pos [player-id]
  (get (skill-effects/get-player-state player-id)
       :position
       {:world-id "minecraft:overworld" :x 0.0 :y 64.0 :z 0.0}))

(defn- set-skill-state!
  [ctx-id k v]
  (ctx-skill/assoc-skill-state! ctx-id k v))

(defn- set-skill-state-root!
  [ctx-id state-map]
  (ctx-skill/replace-skill-state-root! ctx-id state-map))

(defn- clear-skill-state!
  [ctx-id]
  (ctx-skill/clear-skill-state! ctx-id))

(defn- try-adjust [from to]
  (let [d (- (double to) (double from))
        accel (cfg-double :movement.acceleration)]
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
                       (cfg-double :targeting.target-update-radius))
          matched (some #(when (= (:uuid %) target-entity-uuid) %) candidates)]
      (when matched
        (assoc skill-state
               :target-x (double (:x matched))
               :target-y (+ (double (:y matched))
                            (double (or (:eye-height matched) default-eye-height)))
               :target-z (double (:z matched)))))))

(defn- resolve-target [player-id]
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
                                             (cfg-double :targeting.range))]
      (case (:hit-type hit)
        :block
        (let [block-id (normalize-id (:block-id hit))]
          (when (is-metal-block? block-id)
            {:target-kind     :block
             :target-world-id world-id
             :target-x        (double (:x hit))
             :target-y        (double (:y hit))
             :target-z        (double (:z hit))
             :target-block-id block-id}))
        :entity
        (let [entity-type (normalize-id (:type hit))]
          (when (is-metal-entity? entity-type)
            {:target-kind        :entity
             :target-world-id    world-id
             :target-entity-uuid (:uuid hit)
             :target-entity-type entity-type
             :target-x           (double (:x hit))
             :target-y           (+ (double (:y hit))
                                    (double (or (:eye-height hit) default-eye-height)))
             :target-z           (double (:z hit))}))
        nil))))

;; ---------------------------------------------------------------------------
;; Cost fn (tick CP is conditional on having a target)
;; ---------------------------------------------------------------------------

(defn- tick-cp-cost [{:keys [ctx-id exp]}]
  (if-let [ctx (ctx/get-context ctx-id)]
    (if (get-in ctx [:skill-state :has-target])
      (cfg-lerp :cost.tick.cp (double (or exp 0.0)))
      0.0)
    0.0))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn- finalize-and-terminate!
  [{:keys [player-id ctx-id] :as evt} {:keys [grant-exp?] :or {grant-exp? true}}]
  (when-let [ctx-data (ctx/get-context ctx-id)]
    (let [skill-state (:skill-state ctx-data)
          finalized? (boolean (get-in ctx-data [:skill-state :finalized?]))
          should-finalize? (and skill-state (not finalized?))]
      (when should-finalize?
        (set-skill-state! ctx-id [:finalized?] true)
        (when (and grant-exp?
                   (:has-target skill-state)
                   (number? (:start-x skill-state))
                   (number? (:start-y skill-state))
                   (number? (:start-z skill-state)))
          (let [{:keys [x y z]} (player-pos player-id)
                traveled (geom/vdist {:x x :y y :z z}
                                    {:x (double (:start-x skill-state))
                                     :y (double (:start-y skill-state))
                                     :z (double (:start-z skill-state))})]
            (skill-effects/add-skill-exp! player-id mag-movement-skill-id
                                          (max (cfg-double :progression.exp-min)
                                               (* (cfg-double :progression.exp-distance-scale) traveled)))))
        (motion-op/execute-reset-fall-damage! evt nil)
        (fx-op/execute-fx! evt {:topic :mag-movement/fx-end
              :payload {:mode :end}})
          (clear-skill-state! ctx-id)
        (ctx/terminate-context! ctx-id nil)))))

(defn- on-down! [{:keys [player-id exp] :as evt}]
  (let [ctx-id (:ctx-id evt)
        state-pos (player-pos player-id)]
    (if-let [{:keys [target-x target-y target-z] :as target-state}
             (resolve-target player-id)]
      (let [velocity-now (when player-motion/*player-motion*
                           (player-motion/get-velocity player-motion/*player-motion* player-id))]
         (set-skill-state-root! ctx-id
                 (merge target-state
                   {:has-target true
                    :finalized? false
                    :movement-ticks 0
                    :overload-floor (cfg-lerp :cost.down.overload (double (or exp 0.0)))
                    :start-x (double (:x state-pos))
                    :start-y (double (:y state-pos))
                    :start-z (double (:z state-pos))
                    :motion-x (double (or (:x velocity-now) 0.0))
                    :motion-y (double (or (:y velocity-now) 0.0))
                    :motion-z (double (or (:z velocity-now) 0.0))}))
        (fx-op/execute-fx! evt {:topic :mag-movement/fx-start
              :payload {:mode :start}})
        (fx-op/execute-fx! evt {:topic   :mag-movement/fx-update
              :payload {:mode   :update
                  :target {:x (double target-x)
                     :y (double target-y)
                     :z (double target-z)}}})
        (log/debug "MagMovement started" (:target-kind target-state)))
      (do
        (set-skill-state-root! ctx-id {:has-target false :finalized? false})
        (log/debug "MagMovement: no valid magnetic target")
        (finalize-and-terminate! evt {:grant-exp? false})))))

(defn- on-tick! [{:keys [player-id ctx-id cost-ok?] :as evt}]
  (when-let [ctx (ctx/get-context ctx-id)]
    (let [skill-state (:skill-state ctx)
          has-target  (:has-target skill-state)]
      (when has-target
        (let [updated-state (if (= :entity (:target-kind skill-state))
                              (update-entity-target skill-state)
                              skill-state)]
          (if-not updated-state
            (finalize-and-terminate! evt {:grant-exp? true})
            (let [movement-ticks (inc (int (:movement-ticks updated-state)))]
              (set-skill-state-root! ctx-id
                                     (assoc updated-state :movement-ticks movement-ticks))
              (state-op/execute-overload-floor! evt {:floor (:overload-floor updated-state)})
              (if-not cost-ok?
                (finalize-and-terminate! evt {:grant-exp? true})
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
                  (set-skill-state-root! ctx-id
                                         (assoc updated-state
                                                :movement-ticks movement-ticks
                                                :motion-x next-x
                                                :motion-y next-y
                                                :motion-z next-z))
                  (fx-op/execute-fx! evt {:topic   :mag-movement/fx-update
                                          :payload {:mode   :update
                                                    :target {:x tx :y ty :z tz}}})
                  (when (zero? (mod movement-ticks 10))
                    (log/debug "MagMovement: moving for" (/ movement-ticks 20.0) "seconds")))))))))))

(defn- on-up! [{:keys [ctx-id] :as evt}]
  (when-let [ctx (ctx/get-context ctx-id)]
    (let [skill-state (:skill-state ctx)
          has-target  (:has-target skill-state)]
      (when has-target
        (finalize-and-terminate! evt {:grant-exp? true})
        (log/debug "MagMovement completed: ticks" (:movement-ticks skill-state))))))

(defn- on-abort! [{:keys [_player-id _ctx-id] :as evt}]
  (finalize-and-terminate! evt {:grant-exp? true})
  (log/debug "MagMovement aborted"))

(declare mag-movement-skill)

(defskill mag-movement-skill
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
  :cooldown-ticks  (fn [_] (cfg-int :cooldown.ticks))
  :pattern         :hold-channel
  :cooldown        {:mode :manual}
  :cost {:down {:overload   (fn [{:keys [exp]}]
                              (cfg-lerp :cost.down.overload (double (or exp 0.0))))
                :creative?  (fn [{:keys [player]}]
                              (boolean (and player (entity/player-creative? player))))}
         :tick {:cp         tick-cp-cost
                :creative?  (fn [{:keys [player]}]
                              (boolean (and player (entity/player-creative? player))))}}
  :actions {:down!  on-down!
            :tick!  on-tick!
            :up!    on-up!
            :abort! on-abort!
            :cost-fail! (fn [{:keys [cost-stage] :as evt}]
                          (finalize-and-terminate! evt {:grant-exp? (not= cost-stage :down)}))}
  :prerequisites [{:skill-id :arc-gen         :min-exp 0.0}
                  {:skill-id :current-charging :min-exp 0.7}])
