(ns cn.li.ac.content.ability.vecmanip.vec-accel
  "VecAccel skill - dash/acceleration in look direction.
  Aligned with original AcademyCraft VecAccel.scala.

  Mechanics:
  - Charge up to 20 ticks for increased speed
  - Accelerates player in look direction (pitch - 10°)
  - Max velocity: 2.5 blocks/tick
  - Speed: sin(lerp(0.4,1.0,charge/20)) * 2.5
  - Ground check: raycast 2 blocks down (bypassed at exp >= 0.5)
  - Does NOT dismount (original if(getLowestRidingEntity==null) is dead code)
  - Resets fall damage
  - CP: 120-80, Overload: 30-15, Cooldown: 80-50 ticks
  - Experience gain: 0.002 per use (server-side only, no per-tick gain)
  - Client: trajectory preview ribbon (white=can, red=cannot)
  - Client: sound 'vecmanip.vec_accel' on perform

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.service.resource :as res]
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.util.log :as log]))

(def ^:private MAX-VELOCITY 2.5)
(def ^:private MAX-CHARGE 20)

;; ============================================================================
;; Pure helpers
;; ============================================================================

(defn- lerp [a b t]
  (+ (double a) (* (- (double b) (double a)) (double t))))

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (double x))))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :vec-accel :exp] 0.0)))

(defn- calculate-speed
  "Speed = sin(lerp(0.4, 1.0, clamp(0,1, charge/MAX_CHARGE))) * MAX_VELOCITY"
  [charge-ticks]
  (let [prog (lerp 0.4 1.0 (clamp01 (/ (double charge-ticks) MAX-CHARGE)))]
    (* (Math/sin prog) MAX-VELOCITY)))

(defn- cp-cost [exp]   (lerp 120.0 80.0  (clamp01 exp)))
(defn- overload-cost [exp] (lerp 30.0  15.0  (clamp01 exp)))
(defn- cooldown-ticks [exp] (int (lerp 80.0  50.0  (clamp01 exp))))

;; ============================================================================
;; State / resource helpers
;; ============================================================================

(defn- try-consume-resource! [player-id exp]
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data events success?]}
          (res/perform-resource (:resource-data state)
                                player-id
                                (overload-cost exp)
                                (cp-cost exp)
                                false)]
      (when success?
        (ps/update-resource-data! player-id (constantly data))
        (doseq [e events] (ability-evt/fire-ability-event! e)))
      (boolean success?))))

(defn- apply-cooldown! [player-id exp]
  (ps/update-cooldown-data! player-id cd/set-main-cooldown :vec-accel (max 1 (cooldown-ticks exp))))

(defn- add-exp! [player-id amount]
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data events]} (learning/add-skill-exp
                                  (:ability-data state)
                                  player-id
                                  :vec-accel
                                  (double amount)
                                  1.0)]
      (ps/update-ability-data! player-id (constantly data))
      (doseq [e events] (ability-evt/fire-ability-event! e)))))

(defn- get-player-position [player-id]
  (or (when-let [tp (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
        (when-let [tp-impl @tp]
          ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) tp-impl player-id)))
      (get (ps/get-player-state player-id)
           :position
           {:world-id "minecraft:overworld" :x 0.0 :y 64.0 :z 0.0})))

(defn- check-ground-raycast
  "Downward raycast 2 blocks (original: Raytrace p0 → p0.y-2, check BLOCK hit)."
  [player-id]
  (when raycast/*raycast*
    (let [pos      (get-player-position player-id)
          world-id (or (:world-id pos) "minecraft:overworld")
          x        (double (:x pos))
          y        (double (:y pos))
          z        (double (:z pos))]
      (some? (raycast/raycast-blocks raycast/*raycast* world-id x y z 0 -1 0 2.0)))))

;; ============================================================================
;; Velocity calculation (shared between key-tick preview and key-up perform)
;; ============================================================================

(defn- compute-init-vel
  "Return {:x :y :z} initial velocity for given look-dir and charge-ticks."
  [look-dir charge-ticks]
  (let [pitch-adjust -0.174533           ; -10 degrees in radians
        look-x (double (:x look-dir))
        look-y (double (:y look-dir))
        look-z (double (:z look-dir))
        horiz-len (Math/sqrt (+ (* look-x look-x) (* look-z look-z)))
        safe-h    (max 1.0e-8 horiz-len)
        cur-pitch (Math/atan2 (- look-y) safe-h)
        new-pitch (+ cur-pitch pitch-adjust)
        cos-p     (Math/cos new-pitch)
        sin-p     (Math/sin new-pitch)
        hx        (/ look-x safe-h)
        hz        (/ look-z safe-h)
        speed     (calculate-speed charge-ticks)]
    {:x (* cos-p hx speed)
     :y (- (* sin-p speed))
     :z (* cos-p hz speed)}))

;; ============================================================================
;; FX message helpers (server → client via ctx channel)
;; ============================================================================

(defn- send-fx-start! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :vec-accel/fx-start {:mode :start}))

(defn- send-fx-update! [ctx-id charge-ticks can-perform? look-dir init-vel]
  (ctx/ctx-send-to-client! ctx-id :vec-accel/fx-update
                           {:mode        :update
                            :charge-ticks (long (max 0 charge-ticks))
                            :can-perform? (boolean can-perform?)
                            :look-dir    (or look-dir {:x 0.0 :y 0.0 :z 1.0})
                            :init-vel    (or init-vel {:x 0.0 :y 0.0 :z 1.0})}))

(defn- send-fx-perform! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :vec-accel/fx-perform {:mode :perform}))

(defn- send-fx-end! [ctx-id performed?]
  (ctx/ctx-send-to-client! ctx-id :vec-accel/fx-end
                           {:mode :end :performed? (boolean performed?)}))

;; ============================================================================
;; Key handlers (all run server-side via context manager)
;; ============================================================================

(defn vec-accel-on-key-down
  "Initialize charge state; notify client to start trajectory preview."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id assoc :skill-state {:charge-ticks 0 :can-perform false})
    (send-fx-start! ctx-id)
    (log/debug "VecAccel: Charge started")
    (catch Exception e
      (log/warn "VecAccel key-down failed:" (ex-message e)))))

(defn vec-accel-on-key-tick
  "Increment charge, check ground (raycast), send trajectory preview to client."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state  (:skill-state ctx-data)
            charge-ticks (long (or (:charge-ticks skill-state) 0))
            exp          (get-skill-exp player-id)
            new-charge   (min MAX-CHARGE (inc charge-ticks))
            ;; Ground check: raycast OR exp >= 0.5 (original ignoreGroundChecking)
            can-perform? (or (>= (double exp) 0.5) (check-ground-raycast player-id))
            ;; Get look direction for trajectory preview
            look-dir     (when raycast/*raycast*
                           (raycast/get-player-look-vector raycast/*raycast* player-id))
            init-vel     (when look-dir
                           (compute-init-vel look-dir new-charge))]
        (ctx/update-context! ctx-id assoc :skill-state
                             {:charge-ticks new-charge :can-perform can-perform?})
        (send-fx-update! ctx-id new-charge can-perform? look-dir init-vel)))
    (catch Exception e
      (log/warn "VecAccel key-tick failed:" (ex-message e)))))

(defn vec-accel-on-key-up
  "Perform the acceleration: consume resources, apply velocity, cooldown, exp, sound."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state  (:skill-state ctx-data)
            charge-ticks (long (or (:charge-ticks skill-state) 0))
            can-perform? (boolean (or (:can-perform skill-state) false))
            exp          (get-skill-exp player-id)]
        (if-not can-perform?
          (do
            (send-fx-end! ctx-id false)
            (log/debug "VecAccel: Cannot perform (not on ground, exp <50%)"))
          (if-not (try-consume-resource! player-id exp)
            (do
              (send-fx-end! ctx-id false)
              (log/debug "VecAccel: Insufficient CP/overload"))
            (let [look-dir (when raycast/*raycast*
                             (raycast/get-player-look-vector raycast/*raycast* player-id))]
              (if-not look-dir
                (do
                  (send-fx-end! ctx-id false)
                  (log/warn "VecAccel: Could not get look vector"))
                (let [{:keys [x y z]} (compute-init-vel look-dir charge-ticks)]
                  ;; Apply velocity (original: VecUtils.setMotion on client side)
                  (when player-motion/*player-motion*
                    (player-motion/set-velocity! player-motion/*player-motion*
                                                 player-id x y z))
                  ;; NOTE: original dismount check `if(getLowestRidingEntity==null)` is
                  ;; dead code in MC 1.12 (returns self, never null) → no dismount call.
                  ;; Reset fall damage (original: player.fallDistance = 0 on server)
                  (when teleportation/*teleportation*
                    (teleportation/reset-fall-damage! teleportation/*teleportation* player-id))
                  ;; Cooldown (original: ctx.setCooldown on client after consume)
                  (apply-cooldown! player-id exp)
                  ;; Experience (original: ctx.addSkillExp(0.002f) on server)
                  (add-exp! player-id 0.002)
                  ;; Notify client: play sound + end trajectory preview
                  (send-fx-perform! ctx-id)
                  (send-fx-end! ctx-id true)
                  (log/info "VecAccel: Accelerated speed"
                            (format "%.2f" (Math/sqrt (+ (* x x) (* y y) (* z z))))
                            "charge:" charge-ticks))))))))
    (catch Exception e
      (log/warn "VecAccel key-up failed:" (ex-message e)))))

(defn vec-accel-on-key-abort
  "Clean up state on abort; notify client to remove trajectory preview."
  [{:keys [ctx-id]}]
  (try
    (send-fx-end! ctx-id false)
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "VecAccel aborted")
    (catch Exception e
      (log/warn "VecAccel key-abort failed:" (ex-message e)))))
