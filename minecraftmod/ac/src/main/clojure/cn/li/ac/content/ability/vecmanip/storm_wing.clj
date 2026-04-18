(ns cn.li.ac.content.ability.vecmanip.storm-wing
  "StormWing - Level 3 Vector Manipulation skill.

  Toggle flight ability with directional control.

  Mechanics:
  - Charge phase: lerp(70,30,exp) ticks
  - Active flight: server applies velocity from client-reported move direction (WASD),
    or hover when no direction (float up 0.078/tick, or 0.1 near ground)
  - Speed: (if exp<0.45 0.7 1.2) * lerp(2,3,exp)
  - Acceleration: 0.16 per tick toward target velocity
  - Low exp (<15%): tries to break 40 random soft blocks (hardness 0-0.3) in range卤10 each tick
  - Max exp (=1.0): on transition to flight, knockback nearby entities (range 3, strength 2.0)
  - On terminate: set cooldown lerp(30,10,exp) ticks

  Resources (self-managed, defskill has cp/overload=0):
  - CP: lerp(40,25,exp) per active-flight tick
  - Overload: lerp(10,7,exp) per active-flight tick

  Experience:
  - 0.00005 per tick during flight

  No Minecraft imports."
  (:require [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
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

(def ^:private ACCEL 0.16)

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id) [:ability-data :skills :storm-wing :exp] 0.0)))

(defn- get-player-pos [player-id]
  (when teleportation/*teleportation*
    (teleportation/get-player-position teleportation/*teleportation* player-id)))

(defn- apply-cooldown! [player-id exp]
  (let [cd-ticks (int (Math/round (double (bal/lerp 30.0 10.0 exp))))]
    (skill-effects/set-main-cooldown! player-id :storm-wing cd-ticks)))

(defn storm-wing-cost-tick-cp
  [{:keys [player-id]}]
  (bal/lerp 40.0 25.0 (skill-exp player-id)))

(defn storm-wing-cost-tick-overload
  [{:keys [player-id]}]
  (bal/lerp 10.0 7.0 (skill-exp player-id)))

(defn storm-wing-cost-creative?
  [{:keys [player]}]
  (boolean (when player
             (try ((resolve 'cn.li.mcmod.platform.entity/player-creative?) player)
                  (catch Exception _ false)))))

(defn- add-exp! [player-id]
  (skill-effects/add-skill-exp! player-id :storm-wing 0.00005))

(defn- break-soft-blocks! [player-id world-id px py pz]
  (when block-manip/*block-manipulation*
    (let [tries 40]
      (dotimes [_ tries]
        (let [bx (+ (int px) (- (rand-int 21) 10))
              by (+ (int py) (- (rand-int 21) 10))
              bz (+ (int pz) (- (rand-int 21) 10))]
          (when-let [hardness (block-manip/get-block-hardness
                                block-manip/*block-manipulation*
                                world-id bx by bz)]
            (when (and (> hardness 0.0) (<= hardness 0.3))
              (block-manip/break-block! block-manip/*block-manipulation*
                                        player-id world-id bx by bz false))))))))

(defn- knockback-nearby-entities! [player-id world-id px py pz]
  (when (and world-effects/*world-effects* entity-motion/*entity-motion*)
    (let [entities (world-effects/find-entities-in-radius
                     world-effects/*world-effects*
                     world-id (double px) (double py) (double pz) 3.0)]
      (doseq [entity entities
              :let [eid (:uuid entity)]
              :when (not= eid player-id)]
        (let [dx (- (double (:x entity)) (double px))
              dy (- (double (:y entity)) (double py))
              dz (- (double (:z entity)) (double pz))
              dist (max 1.0e-6 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))]
          (entity-motion/add-velocity! entity-motion/*entity-motion*
                                       world-id eid
                                       (* (/ dx dist) 2.0)
                                       (* (/ dy dist) 2.0)
                                       (* (/ dz dist) 2.0)))))))

;; ============================================================================
;; Velocity helpers (smooth accel toward target)
;; ============================================================================

(defn- accel-toward [current target]
  (let [d (- (double target) (double current))]
    (if (< (Math/abs d) ACCEL)
      (double target)
      (if (pos? d)
        (+ (double current) ACCEL)
        (- (double current) ACCEL)))))

;; ============================================================================
;; FX helpers
;; ============================================================================

(defn- send-fx-start! [ctx-id charge-ticks]
  (ctx/ctx-send-to-client! ctx-id :storm-wing/fx-start
                           {:mode :start :charge-ticks (long charge-ticks)}))

(defn- send-fx-update! [ctx-id phase charge-ticks charge-ratio]
  (ctx/ctx-send-to-client! ctx-id :storm-wing/fx-update
                           {:mode :update
                            :phase phase
                            :charge-ticks (long charge-ticks)
                            :charge-ratio (double charge-ratio)}))

(defn- send-fx-end! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :storm-wing/fx-end {:mode :end}))

;; ============================================================================
;; Skill callbacks
;; ============================================================================

(defn storm-wing-on-key-down
  [{:keys [ctx-id player-id]}]
  (try
    (let [exp (skill-exp player-id)
          charge-needed (int (Math/round (double (bal/lerp 70.0 30.0 exp))))]
      (ctx/update-context! ctx-id assoc :skill-state
                           {:phase :charging
                            :charge-ticks 0
                            :charge-ticks-needed charge-needed
                            :vx 0.0 :vy 0.0 :vz 0.0})
      (send-fx-start! ctx-id charge-needed))
    (catch Exception e
      (log/warn "StormWing key-down failed:" (ex-message e)))))

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
                (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] new-ct)
                (send-fx-update! ctx-id :charging new-ct (min 1.0 (/ (double new-ct) (double needed))))
                (when (>= new-ct needed)
                  ;; Transition to flying
                  (ctx/update-context! ctx-id assoc-in [:skill-state :phase] :flying)
                  ;; Max-exp: initial entity knockback
                  (when (>= exp 1.0)
                    (when-let [pos (get-player-pos player-id)]
                      (knockback-nearby-entities! player-id (:world-id pos)
                                                  (:x pos) (:y pos) (:z pos))))))

              :flying
              ;; Consume resources
              (if cost-ok?
                (let [pos (get-player-pos player-id)]
                  (when pos
                    (let [world-id (:world-id pos)
                          px (double (:x pos))
                          py (double (:y pos))
                          pz (double (:z pos))
                          speed (* (if (< exp 0.45) 0.7 1.2) (bal/lerp 2.0 3.0 exp))
                          ;; Get current move direction from context (set by client)
                          dir (:move-dir skill-state)
                          cur-vx (double (:vx skill-state 0.0))
                          cur-vy (double (:vy skill-state 0.0))
                          cur-vz (double (:vz skill-state 0.0))]

                      ;; Low exp: break soft blocks
                      (when (< exp 0.15)
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
                                                               px (+ py 1.62) pz
                                                               0.0 -1.0 0.0
                                                               2.0)]
                                                     (boolean hit)))
                                    hover-vy (if near-ground? 0.1 0.078)]
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
                        (ctx/update-context! ctx-id update :skill-state
                                             assoc :vx new-vx :vy new-vy :vz new-vz)

                        ;; Grant exp
                        (add-exp! player-id)

                        ;; FX update
                        (send-fx-update! ctx-id :flying 0 1.0)))))

                ;; Insufficient resources: terminate
                (do
                  (apply-cooldown! player-id exp)
                  (send-fx-end! ctx-id)
                  (ctx/update-context! ctx-id assoc-in [:skill-state :phase] :terminated)))

              nil)))))
    (catch Exception e
      (log/warn "StormWing key-tick failed:" (ex-message e)))))

(defn storm-wing-on-key-up
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (skill-exp player-id)]
      (apply-cooldown! player-id exp)
      (send-fx-end! ctx-id)
      (ctx/update-context! ctx-id dissoc :skill-state))
    (catch Exception e
      (log/warn "StormWing key-up failed:" (ex-message e)))))

(defn storm-wing-on-key-abort
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (skill-exp player-id)]
      (apply-cooldown! player-id exp)
      (send-fx-end! ctx-id)
      (ctx/update-context! ctx-id dissoc :skill-state))
    (catch Exception e
      (log/warn "StormWing key-abort failed:" (ex-message e)))))

(defn storm-wing-on-move-dir
  "Called when client sends updated movement direction via context channel.
  payload: {:x dx :y dy :z dz} world-space unit vector, or nil for no movement."
  [{:keys [ctx-id payload]}]
  (ctx/update-context! ctx-id assoc-in [:skill-state :move-dir] payload))

(defskill! storm-wing
  :id :storm-wing
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.storm_wing"
  :description-key "ability.skill.vecmanip.storm_wing.desc"
  :icon "textures/abilities/vecmanip/skills/storm_wing.png"
  :ui-position [130 20]
  :level 3
  :controllable? true
  :ctrl-id :storm-wing
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
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
  :prerequisites [{:skill-id :vec-accel :min-exp 0.6}])

