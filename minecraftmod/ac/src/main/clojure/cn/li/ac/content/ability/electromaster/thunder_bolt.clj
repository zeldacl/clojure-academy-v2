(ns cn.li.ac.content.ability.electromaster.thunder-bolt
  "ThunderBolt skill - instant targeted lightning strike with AOE damage.

  Aligned to original AcademyCraft ThunderBolt.scala behavior:
  - Instant cast (on key down)
  - Consumes overload lerp(50,27) and CP lerp(280,420) by exp
  - Raycast combined (entity+block) at range 20; end point:
      no hit  -> eye_pos + look_dir * 20
      entity  -> hitVec + (0, eye_height, 0)
      block   -> hitVec
  - Spawn lightning bolt at end point
  - Direct damage lerp(10,25) to primary target (entity hit)
  - AOE damage lerp(6,15) to living entities within radius 8 of end point
  - 80% chance Slowness IV (amplifier 3) 40 ticks on primary target when exp > 0.2
  - Original also re-applies Slowness IV 20 ticks to primary target per AOE entity
  - Exp: +0.005 if effective, +0.003 if not
  - Cooldown: lerp(120,50) ticks
  - Sends :thunder-bolt/fx-perform to client for arc + sound effects

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.service.resource :as res]
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Constants (matching original ThunderBolt.scala)
;; ============================================================================

(def ^:private range-max 20.0)
(def ^:private aoe-range 8.0)
(def ^:private eye-height 1.62)

;; ============================================================================
;; Private helpers
;; ============================================================================

(defn- lerp [a b t]
  (+ (double a) (* (- (double b) (double a)) (double t))))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :thunder-bolt :exp] 0.0)))

(defn- player-world-id [player-id]
  (or (get-in (ps/get-player-state player-id) [:position :world-id])
      "minecraft:overworld"))

(defn- consume-cost!
  "Consume overload lerp(50,27,exp) and CP lerp(280,420,exp).
  Returns true if resources were available."
  [player-id exp]
  (when-let [state (ps/get-player-state player-id)]
    (let [overload (lerp 50.0 27.0 exp)
          cp       (lerp 280.0 420.0 exp)
          {:keys [data success? events]} (res/perform-resource
                                           (:resource-data state)
                                           player-id
                                           overload cp false)]
      (when success?
        (ps/update-resource-data! player-id (constantly data))
        (doseq [e events]
          (ability-evt/fire-ability-event! e)))
      (boolean success?))))

(defn- apply-cooldown!
  "Set cooldown to lerp(120,50,exp) ticks."
  [player-id exp]
  (let [cd-ticks (int (Math/round (double (lerp 120.0 50.0 exp))))]
    (ps/update-cooldown-data! player-id cd/set-main-cooldown :thunder-bolt (max 1 cd-ticks))))

(defn- add-exp!
  "Grant 0.005 exp if effective (hit something), 0.003 otherwise."
  [player-id effective?]
  (when-let [state (ps/get-player-state player-id)]
    (let [amount (if effective? 0.005 0.003)
          {:keys [data events]} (learning/add-skill-exp
                                  (:ability-data state)
                                  player-id
                                  :thunder-bolt
                                  amount 1.0)]
      (ps/update-ability-data! player-id (constantly data))
      (doseq [e events]
        (ability-evt/fire-ability-event! e)))))

;; ============================================================================
;; Skill handlers
;; ============================================================================

(defn thunder-bolt-on-key-down
  "Execute ThunderBolt skill on key down (instant cast)."
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp          (get-skill-exp player-id)
          consumed?    (consume-cost! player-id exp)]
      (when consumed?
        (let [world-id     (player-world-id player-id)
              damage-direct (lerp 10.0 25.0 exp)
              damage-aoe    (lerp 6.0 15.0 exp)

              ;; Player eye position
              player-state  (ps/get-player-state player-id)
              player-pos    (get player-state :position {:x 0.0 :y 64.0 :z 0.0})
              eye-x         (double (:x player-pos))
              eye-y         (+ (double (:y player-pos)) eye-height)
              eye-z         (double (:z player-pos))

              ;; Look direction (keys are :x :y :z)
              look-vec      (when raycast/*raycast*
                              (raycast/get-player-look-vector raycast/*raycast* player-id))
              dx            (double (or (:x look-vec) 0.0))
              dy            (double (or (:y look-vec) 0.0))
              dz            (double (or (:z look-vec) 1.0))

              ;; Raycast combined: prefer entity, fall back to block
              hit           (when (and raycast/*raycast* look-vec)
                              (raycast/raycast-combined raycast/*raycast*
                                                        world-id
                                                        eye-x eye-y eye-z
                                                        dx dy dz
                                                        range-max))

              ;; End point of the strike (AOE center)
              [end-x end-y end-z]
              (if-not hit
                [(+ eye-x (* dx range-max))
                 (+ eye-y (* dy range-max))
                 (+ eye-z (* dz range-max))]
                (let [hx (double (get hit :x 0.0))
                      hy (double (get hit :y 0.0))
                      hz (double (get hit :z 0.0))]
                  (if (= (:hit-type hit) :entity)
                    [hx (+ hy eye-height) hz]
                    [hx hy hz])))

              hit-entity?   (= (:hit-type hit) :entity)
              target-uuid   (when hit-entity? (:uuid hit))

              ;; Find all living entities in AOE radius (excluding caster and primary target)
              nearby        (when world-effects/*world-effects*
                              (world-effects/find-entities-in-radius
                                world-effects/*world-effects*
                                world-id end-x end-y end-z aoe-range))
              aoe-entities  (vec (filter (fn [{:keys [uuid]}]
                                           (and (not= uuid player-id)
                                                (not= uuid target-uuid)))
                                         nearby))

              effective?    (or hit-entity? (seq aoe-entities))]

          ;; Spawn lightning at strike point
          (when world-effects/*world-effects*
            (world-effects/spawn-lightning! world-effects/*world-effects*
                                            world-id end-x end-y end-z))

          ;; Direct damage to primary target
          (when (and hit-entity? entity-damage/*entity-damage*)
            (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                world-id target-uuid
                                                damage-direct :lightning))

          ;; Slowness IV (amplifier 3), 40 ticks on primary target at exp > 0.2 with 80% chance
          (when (and hit-entity?
                     (> (double exp) 0.2)
                     (< (rand) 0.8)
                     potion-effects/*potion-effects*)
            (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                                 target-uuid :slowness 40 3))

          ;; AOE damage + re-apply slowness to primary target per original behavior
          (doseq [{:keys [uuid]} aoe-entities]
            (when entity-damage/*entity-damage*
              (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                  world-id uuid
                                                  damage-aoe :lightning))
            ;; Original applies slowness to ad.target (not the AOE entity) inside this loop
            (when (and target-uuid
                       (> (double exp) 0.2)
                       (< (rand) 0.8)
                       potion-effects/*potion-effects*)
              (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                                   target-uuid :slowness 20 3)))

          ;; Send client effect: 3 main arcs + AOE arcs + sound
          (ctx/ctx-send-to-client! ctx-id :thunder-bolt/fx-perform
                                   {:start     {:x eye-x :y eye-y :z eye-z}
                                    :end       {:x end-x :y end-y :z end-z}
                                    :aoe-points (mapv (fn [{:keys [x y z]}]
                                                        {:x (double x)
                                                         :y (+ (double y) eye-height)
                                                         :z (double z)})
                                                      aoe-entities)})

          ;; Exp and cooldown
          (add-exp! player-id effective?)
          (apply-cooldown! player-id exp)

          (log/debug "ThunderBolt executed at" end-x end-y end-z
                     "effective?" effective? "aoe-count" (count aoe-entities)))))
    (catch Exception e
      (log/warn "ThunderBolt execution failed:" (ex-message e))))
  ;; Always terminate (instant skill)
  (ctx/terminate-context! ctx-id nil))

(defn thunder-bolt-on-key-tick
  "ThunderBolt is instant cast, no tick behavior."
  [_ctx]
  nil)

(defn thunder-bolt-on-key-up
  "ThunderBolt is instant cast, no key up behavior."
  [_ctx]
  nil)

(defn thunder-bolt-on-key-abort
  "ThunderBolt is instant cast, no abort behavior."
  [_ctx]
  nil)
