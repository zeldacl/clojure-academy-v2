(ns cn.li.ac.content.ability.teleporter.shift-teleport
  "ShiftTeleport skill - teleport to target location while transporting a held item.

  Pattern: :release-cast
  Mechanic: Teleport player to look target. If player is holding a block item,
            also place that block at the destination offset (cosmetic shift mechanic).
            The block consumption is handled via bm/consume-player-item (if supported),
            otherwise the block is placed without consuming.
  Range: lerp(20, 35, exp)
  CP cost: lerp(120, 80, exp)
  Overload: lerp(50, 35, exp)
  Cooldown: lerp(25, 15, exp) ticks
  Exp: +0.002 per success

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.mcmod.platform.block-manipulation :as bm]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private shift-teleport-skill-id :shift-teleport)

(defn- raycast-block-target
  "Raycast for block target at given range, returns {:x :y :z} or nil."
  [player-id max-range]
  (when raycast/*raycast*
    (let [player-pos (helper/player-position player-id)
          look-vec   (helper/player-look-vec player-id)]
      (when (and player-pos look-vec)
        (let [world-id (geom/world-id-of player-id)
              eye-x (+ (double (:x player-pos)) 0.0)
              eye-y (+ (double (:y player-pos))
                       (helper/cfg-double shift-teleport-skill-id :targeting.eye-height))
              eye-z (+ (double (:z player-pos)) 0.0)]
          (raycast/raycast-blocks
            raycast/*raycast*
            world-id
            eye-x eye-y eye-z
            (double (:x look-vec))
            (double (:y look-vec))
            (double (:z look-vec))
            (double max-range)))))))

(defn- segment-intersects-aabb?
  "Return true when segment p0->p1 intersects axis-aligned box {min,max}."
  [{:keys [x y z]} p1 {:keys [min-x min-y min-z max-x max-y max-z]}]
  (let [dx (- (double (:x p1)) (double x))
        dy (- (double (:y p1)) (double y))
        dz (- (double (:z p1)) (double z))
        axis-step (fn [p d mn mx tmin tmax]
                    (if (< (Math/abs d) 1.0e-9)
                      (if (or (< p mn) (> p mx)) nil [tmin tmax])
                      (let [inv (/ 1.0 d)
                            t1 (* (- mn p) inv)
                            t2 (* (- mx p) inv)
                            lo (min t1 t2)
                            hi (max t1 t2)
                            ntmin (max tmin lo)
                            ntmax (min tmax hi)]
                        (when (<= ntmin ntmax)
                          [ntmin ntmax]))))]
    (when-let [[tmin tmax] (axis-step (double x) dx (double min-x) (double max-x) 0.0 1.0)]
      (when-let [[tmin tmax] (axis-step (double y) dy (double min-y) (double max-y) tmin tmax)]
        (when-let [[_ _] (axis-step (double z) dz (double min-z) (double max-z) tmin tmax)]
          true)))))

(defn- entity-aabb
  [entity]
  (let [x (double (:x entity))
        y (double (:y entity))
        z (double (:z entity))
        half-w (/ (double (or (:width entity) 0.6)) 2.0)
        h (double (or (:height entity) 1.8))]
    {:min-x (- x half-w)
     :max-x (+ x half-w)
     :min-y y
     :max-y (+ y h)
     :min-z (- z half-w)
     :max-z (+ z half-w)}))

(defn- point-line-distance-sq
  [{sx :x sy :y sz :z} {ex :x ey :y ez :z} {px :x py :y pz :z}]
  (let [vx (- (double ex) (double sx))
        vy (- (double ey) (double sy))
        vz (- (double ez) (double sz))
        wx (- (double px) (double sx))
        wy (- (double py) (double sy))
        wz (- (double pz) (double sz))
        len-sq (+ (* vx vx) (* vy vy) (* vz vz))
        t (if (pos? len-sq)
            (max 0.0 (min 1.0 (/ (+ (* wx vx) (* wy vy) (* wz vz)) len-sq)))
            0.0)
        qx (+ (double sx) (* vx t))
        qy (+ (double sy) (* vy t))
        qz (+ (double sz) (* vz t))
        dx (- (double px) qx)
        dy (- (double py) qy)
        dz (- (double pz) qz)]
    (+ (* dx dx) (* dy dy) (* dz dz))))

(defn- line-targets
  "Return entities intersecting segment eye->destination in stable near-to-far order."
  [player-id world-id eye-pos dest-pos]
  (if-not (and world-effects/*world-effects* eye-pos dest-pos)
    []
    (let [min-x (min (double (:x eye-pos)) (double (:x dest-pos)))
          min-y (min (double (:y eye-pos)) (double (:y dest-pos)))
          min-z (min (double (:z eye-pos)) (double (:z dest-pos)))
          max-x (max (double (:x eye-pos)) (double (:x dest-pos)))
          max-y (max (double (:y eye-pos)) (double (:y dest-pos)))
          max-z (max (double (:z eye-pos)) (double (:z dest-pos)))
          candidates (world-effects/find-entities-in-aabb
                       world-effects/*world-effects*
                       world-id
                       min-x min-y min-z
                       max-x max-y max-z)]
      (->> candidates
           (filter (fn [entity]
                     (let [uuid (str (:uuid entity))]
                       (and (seq uuid)
                            (not= uuid (str player-id))
                            (segment-intersects-aabb? eye-pos dest-pos (entity-aabb entity))))))
           (sort-by (fn [entity]
                      (point-line-distance-sq eye-pos dest-pos
                                              {:x (:x entity) :y (:y entity) :z (:z entity)})))
           (reduce (fn [acc entity]
                     (let [uuid (str (:uuid entity))]
                       (if (contains? (:seen acc) uuid)
                         acc
                         {:seen (conj (:seen acc) uuid)
                          :entities (conj (:entities acc) entity)})))
                   {:seen #{} :entities []})
           :entities))))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn shift-tp-down!
  [{:keys [ctx-id cost-ok?]}]
  (when cost-ok?
    (ctx/update-context! ctx-id assoc :skill-state {:ticks 0})))

(defn shift-tp-tick!
  [{:keys [ctx-id hold-ticks]}]
  (ctx/update-context! ctx-id assoc-in [:skill-state :ticks] hold-ticks))

(defn shift-tp-up!
  [{:keys [player-id ctx-id]}]
  (try
        (let [exp     (helper/skill-exp player-id shift-teleport-skill-id)
          range   (helper/cfg-lerp shift-teleport-skill-id :targeting.range exp)
          target  (raycast-block-target player-id range)]
      (if target
        (let [world-id (geom/world-id-of player-id)
              eye-y    (+ (double (:y (helper/player-position player-id)))
                          (helper/cfg-double shift-teleport-skill-id :targeting.eye-height))
              eye-pos  {:x (double (:x (helper/player-position player-id)))
                        :y eye-y
                        :z (double (:z (helper/player-position player-id)))}
              line-end {:x (+ (double (:x target)) 0.5)
                        :y (+ (double (:y target)) 0.5)
                        :z (+ (double (:z target)) 0.5)}
              entities (line-targets player-id world-id eye-pos line-end)
              dest-x   (+ (double (:x target)) 0.5)
              dest-y   (double (:y target))
              dest-z   (+ (double (:z target)) 0.5)
              damage   (helper/cfg-lerp shift-teleport-skill-id :combat.damage exp)]
          (when (helper/teleport-to! player-id world-id dest-x dest-y dest-z)
            (doseq [entity entities]
              (let [target-uuid (str (:uuid entity))
                    damage-result (helper/deal-magic-damage! player-id world-id target-uuid damage)]
                (when (:critical? damage-result)
                  (ctx/ctx-send-to-client! ctx-id :teleporter/fx-crit-hit
                                           {:x (double (:x entity))
                                            :y (double (:y entity))
                                            :z (double (:z entity))
                                            :crit-level (:crit-level damage-result)
                                            :target-uuid target-uuid
                                            :skill-id shift-teleport-skill-id}))))
            (skill-effects/add-skill-exp! player-id shift-teleport-skill-id
                                          (helper/cfg-double shift-teleport-skill-id
                                                             :progression.exp-success))
            (let [cd (helper/cfg-lerp-int shift-teleport-skill-id :cooldown.ticks exp)]
              (skill-effects/set-main-cooldown! player-id shift-teleport-skill-id cd))
            (ctx/ctx-send-to-client! ctx-id :shift-tp/fx-perform
                                     {:x dest-x :y dest-y :z dest-z})))
        (log/debug "ShiftTeleport: no block target")))
    (catch Exception e
      (log/warn "ShiftTeleport up! failed:" (ex-message e)))))

(defn shift-tp-abort!
  [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id dissoc :skill-state))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! shift-teleport
  :id             :shift-teleport
  :category-id    :teleporter
  :name-key       "ability.skill.teleporter.shift_teleport"
  :description-key "ability.skill.teleporter.shift_teleport.desc"
  :icon           "textures/abilities/teleporter/skills/shift_teleport.png"
  :ui-position    [60 120]
  :level          4
  :controllable?  true
  :ctrl-id        :shift-teleport
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :release-cast
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (helper/cfg-lerp shift-teleport-skill-id
                                                       :cost.down.cp
                                                       (helper/skill-exp player-id shift-teleport-skill-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (helper/cfg-lerp shift-teleport-skill-id
                                                       :cost.down.overload
                                                       (helper/skill-exp player-id shift-teleport-skill-id)))}}
  :cooldown       {:mode :manual}
  :actions        {:down!  shift-tp-down!
                   :tick!  shift-tp-tick!
                   :up!    shift-tp-up!
                   :abort! shift-tp-abort!}
  :fx             {:start {:topic :shift-tp/fx-start :payload (fn [_] {})}
                   :end   {:topic :shift-tp/fx-end   :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :location-teleport :min-exp 0.5}])
