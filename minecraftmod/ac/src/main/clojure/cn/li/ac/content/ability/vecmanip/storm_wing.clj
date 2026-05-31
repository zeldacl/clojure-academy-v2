(ns cn.li.ac.content.ability.vecmanip.storm-wing
  "StormWing - Level 3 Vector Manipulation skill.

  Toggle flight ability with directional control.

  Mechanics:
  - Charge phase: lerp(70,30,exp) ticks
  - Active flight: server applies velocity from client-reported move direction (WASD),
    or hover when no direction (float up 0.078/tick, or 0.1 near ground)
  - Speed: (if exp<0.45 0.7 1.2) * lerp(2,3,exp)
  - Acceleration: 0.16 per tick toward target velocity
  - Low exp (<15%): tries to break 40 random soft blocks (hardness 0-0.3) in range�?0 each tick
  - Max exp (=1.0): on transition to flight, knockback nearby entities (range 3, strength 2.0)
  - On terminate: set cooldown lerp(30,10,exp) ticks

  Resources (self-managed, defskill has cp/overload=0):
  - CP: lerp(40,25,exp) per active-flight tick
  - Overload: lerp(10,7,exp) per active-flight tick

  Experience:
  - 0.00005 per tick during flight

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.content.ability.fx-helpers :as fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private storm-wing-skill-id :storm-wing)

(defn- exp01 [exp]
  (max 0.0 (min 1.0 (double (or exp 0.0)))))

(defn- cfg-double [field-id]
  (skill-config/tunable-double storm-wing-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int storm-wing-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double storm-wing-skill-id field-id (exp01 exp)))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int storm-wing-skill-id field-id (exp01 exp)))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id storm-wing-skill-id))

(defn- get-player-pos [player-id]
  (when teleportation/*teleportation*
    (teleportation/get-player-position teleportation/*teleportation* player-id)))

(defn- apply-cooldown! [player-id exp]
  (let [cd-ticks (cfg-lerp-int :cooldown.ticks exp)]
    (skill-effects/set-main-cooldown! player-id :storm-wing cd-ticks)))

(defn storm-wing-cost-tick-cp
  [{:keys [player-id]}]
  (cfg-lerp :cost.tick.cp (skill-exp player-id)))

(defn storm-wing-cost-tick-overload
  [{:keys [player-id]}]
  (cfg-lerp :cost.tick.overload (skill-exp player-id)))

(defn storm-wing-cost-creative?
  [{:keys [player]}]
  (boolean (when player
             (try ((resolve 'cn.li.mcmod.platform.entity/player-creative?) player)
                  (catch Exception _ false)))))

(defn- add-exp! [player-id]
  (skill-effects/add-skill-exp! player-id storm-wing-skill-id (cfg-double :progression.exp-tick)))

(defn- safe-context-data
  [ctx-id]
  (try
    (ctx/get-context ctx-id)
    (catch Exception _ nil)))

(defn- command-runtime-ready?
  [{:keys [session-id player-uuid]}]
  (and (runtime-hooks/current-player-state-owner)
       session-id
       player-uuid))

(defn- set-skill-state-root!
  [ctx-id state-map]
  (let [ctx-data (or (safe-context-data ctx-id) {})]
    (if (command-runtime-ready? ctx-data)
      (let [result (command-rt/run-command-in-session! (:session-id ctx-data)
                                                       (:player-uuid ctx-data)
                                                       {:command :context-assoc-skill-state
                                                        :ctx-id ctx-id
                                                        :k []
                                                        :v state-map})]
        (when (= :context-not-found (:rejected-reason result))
          (ctx/update-context! ctx-id assoc :skill-state state-map)))
      (ctx/update-context! ctx-id assoc :skill-state state-map))))

(defn- clear-skill-state!
  [ctx-id]
  (let [ctx-data (or (safe-context-data ctx-id) {})]
    (if (command-runtime-ready? ctx-data)
      (let [result (command-rt/run-command-in-session! (:session-id ctx-data)
                                                       (:player-uuid ctx-data)
                                                       {:command :context-clear-skill-state
                                                        :ctx-id ctx-id})]
        (when (= :context-not-found (:rejected-reason result))
          (ctx/update-context! ctx-id dissoc :skill-state)))
      (ctx/update-context! ctx-id dissoc :skill-state))))

(defn- update-skill-state-root!
  [ctx-id f]
  (let [current (or (:skill-state (or (safe-context-data ctx-id) {})) {})]
    (set-skill-state-root! ctx-id (f current))))

(defn- break-soft-blocks! [player-id world-id px py pz]
  (when block-manip/*block-manipulation*
    (let [tries (cfg-int :breaking.soft-block-tries)
          radius (cfg-int :breaking.soft-block-search-radius)]
      (dotimes [_ tries]
        (let [diameter (inc (* 2 radius))
              bx (+ (int px) (- (rand-int diameter) radius))
              by (+ (int py) (- (rand-int diameter) radius))
              bz (+ (int pz) (- (rand-int diameter) radius))]
          (when-let [hardness (block-manip/get-block-hardness
                                block-manip/*block-manipulation*
                                world-id bx by bz)]
            (when (and (> hardness 0.0) (<= hardness (cfg-double :breaking.soft-hardness-max)))
              (block-manip/break-block! block-manip/*block-manipulation*
                                        player-id world-id bx by bz false))))))))

(defn- knockback-nearby-entities! [player-id world-id px py pz]
  (when (and world-effects/*world-effects* entity-motion/*entity-motion*)
    (let [entities (world-effects/find-entities-in-radius
                     world-effects/*world-effects*
                     world-id (double px) (double py) (double pz) (cfg-double :combat.mastery-knockback-radius))]
      (doseq [entity entities
              :let [eid (:uuid entity)]
              :when (not= eid player-id)]
        (let [dx (- (double (:x entity)) (double px))
              dy (- (double (:y entity)) (double py))
              dz (- (double (:z entity)) (double pz))
              dist (max 1.0e-6 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))]
          (entity-motion/add-velocity! entity-motion/*entity-motion*
                                       world-id eid
                                       (* (/ dx dist) (cfg-double :combat.mastery-knockback-strength))
                                       (* (/ dy dist) (cfg-double :combat.mastery-knockback-strength))
                                       (* (/ dz dist) (cfg-double :combat.mastery-knockback-strength))))))))

;; ============================================================================
;; Velocity helpers (smooth accel toward target)
;; ============================================================================

(defn- accel-toward [current target]
  (let [d (- (double target) (double current))
        accel (cfg-double :movement.acceleration)]
    (if (< (Math/abs d) accel)
      (double target)
      (if (pos? d)
        (+ (double current) accel)
        (- (double current) accel)))))

(defn validate-move-direction
  "Validates and normalizes move direction payload.
   Returns normalized unit vector or nil if invalid/zero."
  [payload]
  (cond
    (nil? payload) nil  ;; nil = hover mode, valid
    (not (map? payload))
    (do (log/warn "StormWing: move-dir payload is not a map:" (type payload)) nil)
    :else
    (let [x (double (or (:x payload) 0.0))
          y (double (or (:y payload) 0.0))
          z (double (or (:z payload) 0.0))
          len-sq (+ (* x x) (* y y) (* z z))
          len (Math/sqrt len-sq)]
      (cond
        (Double/isNaN len)
        (do (log/warn "StormWing: move-dir has NaN") nil)
        (Double/isInfinite len)
        (do (log/warn "StormWing: move-dir has Infinity") nil)
        (> len 1.0e-6)  ;; Non-zero vector
        {:x (/ x len) :y (/ y len) :z (/ z len)}
        :else
        nil))))  ;; Zero vector = hover mode

;; ============================================================================
;; FX helpers
;; ============================================================================

;; ============================================================================
;; Skill callbacks
;; ============================================================================

(defn storm-wing-on-key-down
  [{:keys [ctx-id player-id]}]
  (try
    (let [exp (skill-exp player-id)
          charge-needed (cfg-lerp-int :charge.time exp)]
      (set-skill-state-root! ctx-id
                             {:phase :charging
                              :charge-ticks 0
                              :charge-ticks-needed charge-needed
                              :vx 0.0 :vy 0.0 :vz 0.0})
      (fx/send-start! ctx-id :storm-wing/fx-start {:charge-ticks (long charge-needed)}))
    (catch Exception e
      (log/error "StormWing key-down failed:" e)
      (clear-skill-state! ctx-id))))

(defn storm-wing-on-key-tick
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)]
        (when skill-state
          (let [phase (:phase skill-state)
                exp   (skill-exp player-id)]
            (case phase
              :charging
              (let [ct     (long (:charge-ticks skill-state 0))
                    needed (long (:charge-ticks-needed skill-state 70))
                    new-ct (inc ct)]
                (update-skill-state-root! ctx-id #(assoc % :charge-ticks new-ct))
                (fx/send-update! ctx-id :storm-wing/fx-update
                                {:phase :charging
                                 :charge-ticks (long new-ct)
                                 :charge-ratio (min 1.0 (/ (double new-ct) (double needed)))})
                (when (>= new-ct needed)
                  ;; Transition to flying
                  (update-skill-state-root! ctx-id #(assoc % :phase :flying))
                  ;; Max-exp: initial entity knockback
                  (when (>= exp 1.0)
                    (when-let [pos (get-player-pos player-id)]
                      (knockback-nearby-entities! player-id (:world-id pos)
                                                  (:x pos) (:y pos) (:z pos))))))

              :flying
              ;; Consume resources
              (if cost-ok?
                (let [pos (get-player-pos player-id)]
                  (if pos
                    (let [world-id (:world-id pos)
                          px (double (:x pos))
                          py (double (:y pos))
                          pz (double (:z pos))
                          [low-speed high-speed] (skill-config/tunable-double-list storm-wing-skill-id :movement.speed-multipliers)
                          speed (* (if (< exp (cfg-double :movement.speed-exp-threshold)) low-speed high-speed)
                                   (cfg-lerp :movement.speed-scale exp))
                          ;; Get current move direction from context (set by client)
                          dir (:move-dir skill-state)
                          cur-vx (double (:vx skill-state 0.0))
                          cur-vy (double (:vy skill-state 0.0))
                          cur-vz (double (:vz skill-state 0.0))]

                      ;; Low exp: break soft blocks
                      (when (< exp (cfg-double :breaking.low-exp-threshold))
                        (break-soft-blocks! player-id world-id px py pz))

                      ;; Compute target velocity
                      (let [[tvx tvy tvz]
                            (if (and dir (map? dir))
                              (let [dx (double (or (:x dir) 0.0))
                                    dy (double (or (:y dir) 0.0))
                                    dz (double (or (:z dir) 0.0))
                                    len (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
                                    inv (if (> len 1.0e-6) (/ 1.0 len) 0.0)]
                                [(* dx inv speed) (* dy inv speed) (* dz inv speed)])
                              ;; No movement: hover - check ground proximity
                              (let [;; Raycast down to detect near ground
                                    near-ground? (when raycast/*raycast*
                                                   (let [hit (raycast/raycast-blocks
                                                               raycast/*raycast*
                                                               world-id
                                                               px (+ py (cfg-double :targeting.near-ground-eye-height)) pz
                                                               0.0 -1.0 0.0
                                                               (cfg-double :targeting.near-ground-distance))]
                                                     (boolean hit)))
                                    hover-vy (if near-ground?
                                               (cfg-double :movement.hover-near-ground-velocity)
                                               (cfg-double :movement.hover-air-velocity))]
                                [0.0 hover-vy 0.0]))

                            new-vx (accel-toward cur-vx tvx)
                            new-vy (accel-toward cur-vy tvy)
                            new-vz (accel-toward cur-vz tvz)]

                        ;; Apply velocity to player
                        (when player-motion/*player-motion*
                          (player-motion/set-velocity! player-motion/*player-motion*
                                                       player-id new-vx new-vy new-vz))

                        ;; Reset fall distance (done via motion)
                        (when player-motion/*player-motion*
                          (player-motion/set-on-ground! player-motion/*player-motion* player-id false))

                        ;; Save velocity in state
                        (update-skill-state-root! ctx-id #(assoc % :vx new-vx :vy new-vy :vz new-vz))

                        ;; Grant exp
                        (if-let [exp-result (try (add-exp! player-id) (catch Exception _ nil))]
                          nil
                          (log/warn "StormWing: Failed to grant exp to player" player-id))

                        ;; FX update
                        (fx/send-update! ctx-id :storm-wing/fx-update
                                        {:phase :flying
                                         :charge-ticks 0
                                         :charge-ratio 1.0})))
                    ;; pos为nil，无法继续飞行，立即终止
                    (do
                      (log/warn "StormWing: Player position unavailable, terminating")
                      (apply-cooldown! player-id exp)
                      (fx/send-end! ctx-id :storm-wing/fx-end)
                      (update-skill-state-root! ctx-id #(assoc % :phase :terminated))))

                ;; Insufficient resources: terminate
                (do
                  (apply-cooldown! player-id exp)
                  (fx/send-end! ctx-id :storm-wing/fx-end)
                  (update-skill-state-root! ctx-id #(assoc % :phase :terminated))))))))))
    (catch Exception e
      (log/error "StormWing key-tick failed:" e)
      ;; 主动终止技能状态，防止无限飞行
      (when-let [exp (try (skill-exp player-id) (catch Exception _ 0.0))]
        (apply-cooldown! player-id exp))
      (fx/send-end! ctx-id :storm-wing/fx-end)
      (update-skill-state-root! ctx-id #(assoc % :phase :terminated)))))

(defn storm-wing-on-key-up
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (skill-exp player-id)]
      (apply-cooldown! player-id exp)
      (fx/send-end! ctx-id :storm-wing/fx-end)
      (clear-skill-state! ctx-id))
    (catch Exception e
      (log/error "StormWing key-up failed:" e)
      (clear-skill-state! ctx-id))))

(defn storm-wing-on-key-abort
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (skill-exp player-id)]
      (apply-cooldown! player-id exp)
      (fx/send-end! ctx-id :storm-wing/fx-end)
      (clear-skill-state! ctx-id))
    (catch Exception e
      (log/error "StormWing key-abort failed:" e)
      (clear-skill-state! ctx-id))))

(defn storm-wing-on-move-dir
  "Called when client sends updated movement direction via context channel.
  payload: {:x dx :y dy :z dz} world-space unit vector, or nil for no movement."
  [{:keys [ctx-id payload]}]
  (let [validated-dir (validate-move-direction payload)]
    (update-skill-state-root! ctx-id #(assoc % :move-dir validated-dir))))

(defskill storm-wing
  :id :storm-wing
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.storm_wing"
  :description-key "ability.skill.vecmanip.storm_wing.desc"
  :icon "textures/abilities/vecmanip/skills/storm_wing.png"
  :ui-position [130 20]
  :level 3
  :controllable? true
  :ctrl-id :storm-wing
  :cp-consume-speed 1.0
  :overload-consume-speed 1.0
  :cooldown-ticks 0
  :pattern :release-cast
  :cooldown {:mode :manual}
  :cost {:tick {:cp storm-wing-cost-tick-cp
                :overload storm-wing-cost-tick-overload
                :creative? storm-wing-cost-creative?}}
  :actions {:down! storm-wing-on-key-down
            :tick! storm-wing-on-key-tick
            :up! storm-wing-on-key-up
            :abort! storm-wing-on-key-abort}
  :prerequisites [{:skill-id :vec-accel :min-exp 0.0}])

