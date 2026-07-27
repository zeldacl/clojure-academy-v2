(ns cn.li.ac.content.ability.meltdowner.ray-barrage
  "RayBarrage skill - dual branch behavior.

  Branch A (plain): precise single-target hitscan raytrace, matching
  original's Raytrace.getLookingPos — not an AOE beam.
  Branch B (scattered): when the first thing in the crosshair is an
  in-flight, not-yet-hit silbarn, force-detonate it and hit EVERY entity
  within a cone centered on the player's CURRENT aim (55 degrees yaw span,
  110 degrees pitch span — matches original's asymmetric range/2 vs range
  reuse of a single constant), independent of the silbarn's own position.

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [clojure.string :as str]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def-skill-config-ops :ray-barrage)
(def ^:private ray-barrage-skill-id :ray-barrage)

(defn reset-ray-barrage-state-for-test!
  []
  nil)

(defn- silbarn-type?
  [entity-type]
  (let [s (some-> entity-type str str/lower-case)]
    (boolean (and s (str/includes? s "silbarn")))))

(defn- normalize-look-dir
  [look-vec]
  (let [dx (double (or (:dx look-vec) (:x look-vec) 0.0))
        dy (double (or (:dy look-vec) (:y look-vec) 0.0))
        dz (double (or (:dz look-vec) (:z look-vec) 1.0))
        len (max 1.0e-6 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))]
    {:dx (/ dx len) :dy (/ dy len) :dz (/ dz len)}))

;; Matches original's eyaw/epitch formulas exactly (fixing the obvious
;; dz*dz+dz*dz typo in original's pitch horizontal-distance calc — the
;; correct term is dx*dx+dz*dz). Applying the same formula to a look
;; DIRECTION vector (instead of a relative position delta) recovers the
;; player's own yaw/pitch, since Minecraft derives the look vector from
;; yaw/pitch via the inverse of this exact relationship.
(defn- yaw-degrees
  [dx dz]
  (- (Math/toDegrees (Math/atan2 (double dx) (double dz)))))

(defn- pitch-degrees
  [dx dy dz]
  (- (Math/toDegrees (Math/atan2 (double dy)
                                  (Math/sqrt (+ (* (double dx) (double dx))
                                                (* (double dz) (double dz))))))))

(defn- normalize-angle
  [a]
  (let [m (mod (double a) 360.0)]
    (if (> m 180.0) (- m 360.0) m)))

(defn- yaw-in-range?
  "Wraparound-safe yaw-in-[min,max] check, matching original's
  MathUtils.angleYawinRange."
  [min-yaw max-yaw yaw]
  (let [span (- (double max-yaw) (double min-yaw))]
    (if (>= span 360.0)
      true
      (<= 0.0 (normalize-angle (- (double yaw) (double min-yaw))) span))))

(defn- raycast-front-hit
  [world-id eye look-dir]
  (when (raycast/available?)
    (raycast/raycast-combined
                              world-id
                              (double (:x eye)) (double (:y eye)) (double (:z eye))
                              (double (:dx look-dir)) (double (:dy look-dir)) (double (:dz look-dir))
                              (double (cfg-double :targeting.range)))))

(defn- front-hit-end
  [eye look-dir front-hit]
  (if (and (map? front-hit)
           (some? (or (:x front-hit) (:hit-x front-hit)))
           (some? (or (:y front-hit) (:hit-y front-hit)))
           (some? (or (:z front-hit) (:hit-z front-hit))))
    {:x (double (or (:x front-hit) (:hit-x front-hit)))
     :y (double (or (:y front-hit) (:hit-y front-hit)))
     :z (double (or (:z front-hit) (:hit-z front-hit)))}
    (let [dist (double (cfg-double :targeting.range))]
      {:x (+ (double (:x eye)) (* dist (double (:dx look-dir))))
       :y (+ (double (:y eye)) (* dist (double (:dy look-dir))))
       :z (+ (double (:z eye)) (* dist (double (:dz look-dir))))})))

(defn- send-preray-fx!
  [ctx-id eye target-end hit?]
  (fx/send-local-and-nearby! ctx-id {:topic :ray-barrage/fx-preray} nil
                               {:start {:x (:x eye) :y (:y eye) :z (:z eye)}
                                :end {:x (:x target-end) :y (:y target-end) :z (:z target-end)}
                                :hit? (boolean hit?)}))

(defn- send-barrage-fx!
  [ctx-id silbarn-hit]
  (fx/send-local-and-nearby! ctx-id {:topic :ray-barrage/fx-barrage} nil
                               {:silbarn {:x (double (or (:x silbarn-hit) (:hit-x silbarn-hit) 0.0))
                                          :y (double (or (:y silbarn-hit) (:hit-y silbarn-hit) 0.0))
                                          :z (double (or (:z silbarn-hit) (:hit-z silbarn-hit) 0.0))}}))

;; Purely visual — an extra glowing beam line on top of original's own
;; preray/barrage entity effects. Sent independently of damage so removing
;; the old beam/execute-beam! damage mechanic doesn't lose this upgrade.
(defn- send-beam-line-fx!
  [ctx-id start end]
  (fx/send-local-and-nearby! ctx-id {:topic :ray-barrage/fx-beam} nil
                               {:start {:x (:x start) :y (:y start) :z (:z start)}
                                :end {:x (:x end) :y (:y end) :z (:z end)}}))

(defn- attack!
  "Matches original's MDDamageHelper.attack: apply damage, then let the
  attacker's RadIntensify (if learned) mark the target."
  [player-id ctx-id world-id target-id damage]
  (when (and target-id (entity-damage/available?))
    (entity-damage/apply-direct-damage! world-id target-id (double damage) :magic)
    (md-damage/mark-target! player-id target-id {:ctx-id ctx-id})
    true))

(defn- cone-scatter-targets
  "All entities (excluding player + silbarn) within original's cone: yaw
  spans cone-angle-degrees total, pitch spans cone-angle-degrees on EACH
  side — both centered on the player's CURRENT aim, not the silbarn."
  [world-id player-id silbarn-uuid eye look-dir]
  (if-not (world-effects/available?)
    []
    (let [range        (double (cfg-double :targeting.range))
          cone-angle    (double (cfg-double :scatter.cone-angle-degrees))
          player-yaw   (yaw-degrees (:dx look-dir) (:dz look-dir))
          player-pitch (pitch-degrees (:dx look-dir) (:dy look-dir) (:dz look-dir))
          half-yaw     (/ cone-angle 2.0)
          min-yaw      (- player-yaw half-yaw)
          max-yaw      (+ player-yaw half-yaw)
          min-pitch    (- player-pitch cone-angle)
          max-pitch    (+ player-pitch cone-angle)]
      (->> (world-effects/find-entities-in-radius world-id (:x eye) (:y eye) (:z eye) range)
           (remove (fn [{:keys [uuid]}]
                     (or (= (str uuid) (str player-id))
                         (= (str uuid) silbarn-uuid))))
           (filter (fn [{:keys [x y z eye-height]}]
                     (let [dx (- (double x) (double (:x eye)))
                           dy (- (+ (double y) (double (or eye-height 0.0))) (double (:y eye)))
                           dz (- (double z) (double (:z eye)))
                           target-yaw (yaw-degrees dx dz)
                           target-pitch (pitch-degrees dx dy dz)]
                       (and (yaw-in-range? min-yaw max-yaw target-yaw)
                            (<= min-pitch target-pitch max-pitch)))))))))

;; ---------------------------------------------------------------------------
;; Action
;; ---------------------------------------------------------------------------

(defn ray-barrage-perform!
  [ctx-id player-id _skill-id exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (try
    (let [plain-damage   (cfg-lerp :combat.damage.plain exp)
          scatter-damage (cfg-lerp :combat.damage.scattered exp)
          world-id       (geom/world-id-of player-id)
          eye            (geom/eye-pos player-id)
          look-vec       (when (raycast/available?)
                           (raycast/player-look-vector player-id))
          look-dir       (when look-vec (normalize-look-dir look-vec))]
      (when look-dir
        (let [front-hit      (raycast-front-hit world-id eye look-dir)
              silbarn-hit?   (and (= :entity (:hit-type front-hit))
                                  (silbarn-type? (:type front-hit)))
              silbarn-ready? (and silbarn-hit?
                                  (not (true? (:is-hit front-hit)))
                                  (some? (:uuid front-hit)))]
          (cond
            silbarn-ready?
            (do
              (world-effects/trigger-behavior-hit! world-id (str (:uuid front-hit)))
              (send-preray-fx! ctx-id eye front-hit true)
              (send-barrage-fx! ctx-id front-hit)
              (let [targets (cone-scatter-targets world-id player-id (str (:uuid front-hit)) eye look-dir)]
                (doseq [{:keys [uuid x y z]} targets]
                  (attack! player-id ctx-id world-id uuid scatter-damage)
                  (send-beam-line-fx! ctx-id eye {:x x :y y :z z})))
              (skill-effects/set-main-cooldown! player-id ray-barrage-skill-id
                                                (cfg-lerp-int :cooldown.ticks exp))
              (skill-effects/add-skill-exp! player-id ray-barrage-skill-id
                                            (cfg-double :progression.exp-hit)))

            ;; Not a ready silbarn (either not a silbarn at all, or an
            ;; already-hit one) — original's `hit` flag stays false in both
            ;; cases, falling through to the plain raytrace branch, which
            ;; may re-hit the same (now inert) silbarn as an ordinary target.
            :else
            (let [hit-end (front-hit-end eye look-dir front-hit)
                  hit-uuid (when (= :entity (:hit-type front-hit)) (:uuid front-hit))]
              (when hit-uuid
                (attack! player-id ctx-id world-id hit-uuid plain-damage))
              (send-preray-fx! ctx-id eye hit-end false)
              (send-beam-line-fx! ctx-id eye hit-end)
              (skill-effects/set-main-cooldown! player-id ray-barrage-skill-id
                                                (cfg-lerp-int :cooldown.ticks exp))
              (skill-effects/add-skill-exp! player-id ray-barrage-skill-id
                                            (cfg-double :progression.exp-hit)))))))
    (catch Exception e
      (log/warn "RayBarrage perform! failed:" (ex-message e)))))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill ray-barrage
  :id             :ray-barrage
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.ray_barrage"
  :description-key "ability.skill.meltdowner.ray_barrage.desc"
  :icon           "textures/abilities/meltdowner/skills/ray_barrage.png"
  :ui-position    [140 10]
  :ctrl-id        :ray-barrage
  :pattern        :instant
  :cooldown       {:mode :manual}
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (cfg-lerp :cost.down.cp (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (cfg-lerp :cost.down.overload (skill-exp player-id)))} }
  :cooldown-ticks (fn [{:keys [player-id]}]
                    (cfg-lerp-int :cooldown.ticks (skill-exp player-id)))
  :actions        {:perform! ray-barrage-perform!}
  :prerequisites  [{:skill-id :meltdowner :min-exp 0.5}])
