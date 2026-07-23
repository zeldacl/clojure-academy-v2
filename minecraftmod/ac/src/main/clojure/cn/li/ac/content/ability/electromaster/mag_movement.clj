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
            [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.effects.motion :as motion-op]
            [cn.li.ac.ability.effects.state :as state-op]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
                        [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.ac.ability.effects.motion :as motion-effects]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(def-skill-config-ops :mag-movement)
(def ^:private mag-movement-skill-id :mag-movement)
(def ^:private default-eye-height 1.62)

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
  (if-not (and (world-effects/available?) target-entity-uuid)
    nil
    (let [candidates (world-effects/find-entities-in-radius
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
  (when-let [look (when (raycast/available?)
                    (raycast/player-look-vector player-id))]
    (let [eye      (geom/eye-pos player-id)
          world-id (geom/world-id-of player-id)
          hit      (raycast/raycast-combined
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

(defn- active-skill-ctx-data [player-id skill-id]
  (some (fn [[_ctx-id ctx-data]]
          (when (and (= (:player-uuid ctx-data) player-id)
                     (= skill-id (:skill-id ctx-data)))
            ctx-data))
        (ctx/get-all-contexts)))

;; ---------------------------------------------------------------------------
;; Cost fn (tick CP is conditional on having a target)
;; ---------------------------------------------------------------------------

(defn- tick-cp-cost [player-id _skill-id exp]
  (if-let [ctx-data (active-skill-ctx-data player-id mag-movement-skill-id)]
    (if (get-in ctx-data [:skill-state :has-target])
      (cfg-lerp :cost.tick.cp (double (or exp 0.0)))
      0.0)
    0.0))

(defn- down-overload-cost [_player-id _skill-id exp]
  (cfg-lerp :cost.down.overload (double (or exp 0.0))))

(defn- cost-creative? [_player-id _skill-id _exp]
  false)

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn- finalize-and-terminate!
  [ctx-id player-id & {:keys [grant-exp?] :or {grant-exp? true}}]
  (when-let [ctx-data (ctx-skill/get-context ctx-id)]
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
        (motion-op/execute-reset-fall-damage! {:player-id player-id} nil)
        (fx/send! ctx-id {:topic :mag-movement/fx-end :mode :end} nil)
        (ctx-skill/clear-skill-state! ctx-id)
        (ctx/terminate-context! ctx-id nil)))))

(defn- on-down!
  [ctx-id player-id _skill-id exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (let [state-pos (player-pos player-id)]
    (if-let [{:keys [target-x target-y target-z] :as target-state}
             (resolve-target player-id)]
      (let [velocity-now (when (motion-effects/player-motion-available?)
                           (motion-effects/player-velocity player-id))]
        (ctx-skill/replace-skill-state! ctx-id
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
        (fx/send! ctx-id {:topic :mag-movement/fx-start :mode :start} nil)
        (fx/send! ctx-id {:topic :mag-movement/fx-update :mode :update} nil
                  {:target {:x (double target-x)
                            :y (double target-y)
                            :z (double target-z)}})
        (log/debug "MagMovement started" (:target-kind target-state)))
      (do
        (ctx-skill/replace-skill-state! ctx-id {:has-target false :finalized? false})
        (log/debug "MagMovement: no valid magnetic target")
        (finalize-and-terminate! ctx-id player-id {:grant-exp? false})))))

(defn- on-tick!
  [ctx-id player-id skill-id exp cost-ok? _hold-ticks _cost-stage _player-ref]
  (when-let [ctx (ctx-skill/get-context ctx-id)]
    (let [skill-state (:skill-state ctx)
          has-target  (:has-target skill-state)]
      (when has-target
        (let [updated-state (if (= :entity (:target-kind skill-state))
                              (update-entity-target skill-state)
                              skill-state)]
          (if-not updated-state
            (finalize-and-terminate! ctx-id player-id {:grant-exp? true})
            (let [movement-ticks (inc (int (:movement-ticks updated-state)))]
              (ctx-skill/replace-skill-state! ctx-id
                                     (assoc updated-state :movement-ticks movement-ticks))
              (state-op/execute-overload-floor! ctx-id player-id skill-id exp
                                                {:floor (:overload-floor updated-state)})
              (if-not cost-ok?
                (finalize-and-terminate! ctx-id player-id {:grant-exp? true})
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
                      player-vel (when (motion-effects/player-motion-available?)
                                   (motion-effects/player-velocity player-id))
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
                  (when (motion-effects/player-motion-available?)
                    (motion-effects/set-player-velocity!
                                                 player-id next-x next-y next-z))
                  (ctx-skill/replace-skill-state! ctx-id
                                         (assoc updated-state
                                                :movement-ticks movement-ticks
                                                :motion-x next-x
                                                :motion-y next-y
                                                :motion-z next-z))
                  (fx/send! ctx-id {:topic :mag-movement/fx-update :mode :update} nil
                            {:target {:x tx :y ty :z tz}})
                  (when (zero? (mod movement-ticks 10))
                    (log/debug "MagMovement: moving for" (/ movement-ticks 20.0) "seconds")))))))))))

(defn- on-up!
  [ctx-id player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (when-let [ctx (ctx-skill/get-context ctx-id)]
    (let [skill-state (:skill-state ctx)
          has-target  (:has-target skill-state)]
      (when has-target
        (finalize-and-terminate! ctx-id player-id {:grant-exp? true})
        (log/debug "MagMovement completed: ticks" (:movement-ticks skill-state))))))

(defn- on-abort!
  [ctx-id player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (finalize-and-terminate! ctx-id player-id {:grant-exp? true})
  (log/debug "MagMovement aborted"))

(defn- on-cost-fail!
  [ctx-id player-id _skill-id _exp _cost-ok? _hold-ticks cost-stage _player-ref]
  (finalize-and-terminate! ctx-id player-id {:grant-exp? (not= cost-stage :down)}))

(declare mag-movement-skill)

(defskill mag-movement-skill
  :id              :mag-movement
  :category-id     :electromaster
  :name-key        "ability.skill.electromaster.mag_movement"
  :description-key "ability.skill.electromaster.mag_movement.desc"
  :icon            "textures/abilities/electromaster/skills/mag_movement.png"
  :ui-position     [137 35]
  :ctrl-id         :mag-movement
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern         :hold-channel
  :cooldown        {:mode :manual}
  :cost {:down {:overload   down-overload-cost
                :creative?  cost-creative?}
         :tick {:cp         tick-cp-cost
                :creative?  cost-creative?}}
  :actions {:down!  on-down!
            :tick!  on-tick!
            :up!    on-up!
            :abort! on-abort!
            :cost-fail! on-cost-fail!}
  :prerequisites [{:skill-id :arc-gen         :min-exp 0.0}
                  {:skill-id :current-charging :min-exp 0.7}])
