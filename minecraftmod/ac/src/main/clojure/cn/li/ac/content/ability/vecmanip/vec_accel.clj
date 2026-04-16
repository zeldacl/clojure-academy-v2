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
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.service.skill-effects :as fx-common]
            [cn.li.ac.content.ability.common :as ability-common]
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
  (ability-common/lerp a b t))

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (double x))))

(defn- get-skill-exp [player-id]
  (ability-common/get-skill-exp player-id :vec-accel))

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

(defn vec-accel-cost-up-cp
  [{:keys [player-id]}]
  (cp-cost (get-skill-exp player-id)))

(defn vec-accel-cost-up-overload
  [{:keys [player-id]}]
  (overload-cost (get-skill-exp player-id)))

(defn- apply-cooldown! [player-id exp]
  (ability-common/set-main-cooldown! player-id :vec-accel (cooldown-ticks exp)))

(defn- add-exp! [player-id amount]
  (ability-common/add-skill-exp! player-id :vec-accel amount 1.0))

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
;; DSL actions (used by :pattern :hold-charge-release)
;; ============================================================================

(defn vec-accel-tick!
  [{:keys [player-id ctx-id charge-ticks]}]
  (try
    (let [exp          (get-skill-exp player-id)
          ;; Ground check: raycast OR exp >= 0.5 (original ignoreGroundChecking)
          can-perform? (or (>= (double exp) 0.5) (check-ground-raycast player-id))
          look-dir     (when raycast/*raycast*
                         (raycast/get-player-look-vector raycast/*raycast* player-id))
          init-vel     (when look-dir (compute-init-vel look-dir (long charge-ticks)))]
      (ctx/update-context! ctx-id update :skill-state merge
                           {:can-perform? can-perform?
                            :look-dir look-dir
                            :init-vel init-vel
                            :performed? false}))
    (catch Exception e
      (log/warn "VecAccel tick! failed:" (ex-message e)))))

(defn vec-accel-perform!
  [{:keys [player-id ctx-id charge-ticks cost-ok?]}]
  (try
    (if-not (ctx/get-context ctx-id)
      (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false)
      (let [ctx-data     (ctx/get-context ctx-id)
            skill-state  (:skill-state ctx-data)
            can-perform? (boolean (get skill-state :can-perform? false))
            exp          (get-skill-exp player-id)
            charge       (long (or charge-ticks (get skill-state :charge-ticks) 0))]
        (cond
          (not can-perform?)
          (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false)

          (not cost-ok?)
          (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false)

          :else
          (let [look-dir (or (:look-dir skill-state)
                             (when raycast/*raycast*
                               (raycast/get-player-look-vector raycast/*raycast* player-id)))]
            (if-not look-dir
              (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false)
              (let [{:keys [x y z]} (compute-init-vel look-dir charge)]
                (when player-motion/*player-motion*
                  (player-motion/set-velocity! player-motion/*player-motion*
                                               player-id x y z))
                (when teleportation/*teleportation*
                  (teleportation/reset-fall-damage! teleportation/*teleportation* player-id))
                (apply-cooldown! player-id exp)
                (add-exp! player-id 0.002)
                (ctx/update-context! ctx-id update :skill-state merge
                                     {:performed? true
                                      :final-vel {:x x :y y :z z}})))))))
    (catch Exception e
      (log/warn "VecAccel perform! failed:" (ex-message e)))))

(defn vec-accel-abort!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false))
