(ns cn.li.ac.content.ability.vecmanip.plasma-cannon
  "PlasmaCannon - Level 5 Vector Manipulation skill.

  Aligned 1:1 with original AcademyCraft (Forge 1.12) PlasmaCannon.scala.

  Mechanics:
  - Charge 60→30 ticks (scales with exp, held-key)
  - Per-tick CP drain during charge: 18→25 (scales UP with exp)
  - Initial overload: 500→400 (maintained throughout skill, prevents recovery)
  - On key-release (fully charged): raycast from player eye to first living entity
    or block within 100 blocks → becomes destination
  - Plasma projectile spawns 15 blocks above player, flies at 1 block/tick
  - Max flight time: 240 ticks
  - Explosion: radius 12→15, damage 80→150 in 10-block radius (no friendly fire)
  - Cooldown: 1000→600 ticks (set on fire, scales down with exp)
  - Experience: 0.008 on successful cast
  - Client FX: plasma body effect + tornado at ground + loop sound + charged sound

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.content.ability.fx-helpers :as fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================
;; Scaling helpers (all values from original PlasmaCannon.scala)
;; ============================================================

(def ^:private plasma-cannon-skill-id :plasma-cannon)

(defn- exp01 [exp]
  (max 0.0 (min 1.0 (double (or exp 0.0)))))

(defn- cfg-double [field-id]
  (skill-config/tunable-double plasma-cannon-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int plasma-cannon-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double plasma-cannon-skill-id field-id (exp01 exp)))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int plasma-cannon-skill-id field-id (exp01 exp)))

(defn- charge-time [exp] (cfg-lerp :charge.time exp))
(defn- cp-per-tick [exp] (cfg-lerp :cost.tick.cp exp))
(defn- overload-keep [exp] (cfg-lerp :cost.overload-keep exp))
(defn- damage-amount [exp] (cfg-lerp :combat.damage exp))
(defn- explosion-radius [exp] (cfg-lerp :combat.explosion-radius exp))
(defn- cooldown-ticks [exp] (cfg-lerp-int :cooldown.ticks exp))

;; ============================================================
;; Player state helpers
;; ============================================================

(defn get-skill-exp [player-id]
  (exp01 (skill-effects/skill-exp player-id plasma-cannon-skill-id)))

(defn- get-player-position [player-id]
  (or (when-let [tp (resolve 'cn.li.mcmod.platform.teleportation/*teleportation*)]
        (when-let [impl @tp]
          ((resolve 'cn.li.mcmod.platform.teleportation/get-player-position) impl player-id)))
      (skill-effects/player-path player-id :position {:world-id "minecraft:overworld" :x 0.0 :y 64.0 :z 0.0})))

(defn- get-world-id [player-id]
  (or (skill-effects/player-path player-id [:position :world-id])
      "minecraft:overworld"))

(defn- add-exp! [player-id amount]
  (skill-effects/add-skill-exp! player-id :plasma-cannon amount))

;; Cost DSL hooks (used by content spec :cost)
(defn plasma-cannon-cost-down-overload
  [{:keys [player-id]}]
  (overload-keep (get-skill-exp player-id)))

(defn plasma-cannon-cost-tick-cp
  [{:keys [player-id]}]
  (cp-per-tick (get-skill-exp player-id)))

;; Maintain overload floor each tick (prevent recovery below overload-keep)
(defn- maintain-overload! [player-id min-overload]
  (skill-effects/enforce-overload-floor! player-id min-overload))

(defn- apply-cooldown! [player-id exp]
  (skill-effects/set-main-cooldown! player-id :plasma-cannon (cooldown-ticks exp)))

;; ============================================================
;; FX messaging (server → client)
;; ============================================================

;; ============================================================
;; Projectile movement helpers
;; ============================================================

(defn- try-move [charge-pos destination]
  ;; Advance charge-pos one block toward destination. Returns [new-pos last-pos].
  (let [dx (- (double (:x destination)) (double (:x charge-pos)))
        dy (- (double (:y destination)) (double (:y charge-pos)))
        dz (- (double (:z destination)) (double (:z charge-pos)))
        raw {:x dx :y dy :z dz}
        len (geom/vlen raw)]
    (if (< len 1.0e-6)
      [charge-pos charge-pos]
      (let [step (geom/vnorm raw)]          ; 1 block/tick
        [{:x (+ (double (:x charge-pos)) (:x step))
          :y (+ (double (:y charge-pos)) (:y step))
          :z (+ (double (:z charge-pos)) (:z step))}
         charge-pos]))))

;; Simplified block-hit check: cast ray from last-pos to new-pos
(defn- block-hit? [world-id last-pos new-pos]
  (when raycast/*raycast*
    (let [dx (- (double (:x new-pos)) (double (:x last-pos)))
          dy (- (double (:y new-pos)) (double (:y last-pos)))
          dz (- (double (:z new-pos)) (double (:z last-pos)))
          dist (geom/vlen {:x dx :y dy :z dz})]
      (when (> dist 1.0e-6)
        (let [dir (geom/vnorm {:x dx :y dy :z dz})
              hit (raycast/raycast-blocks raycast/*raycast*
                                         world-id
                                         (double (:x last-pos))
                                         (double (:y last-pos))
                                         (double (:z last-pos))
                                         (:x dir) (:y dir) (:z dir)
                                         (+ dist (cfg-double :projectile.block-hit-extra-distance)))]
          (some? hit))))))

;; ============================================================
;; Explosion logic (equivalent to original explode() method)
;; ============================================================

(defn- do-explode! [player-id world-id destination exp]
  (let [tx (double (:x destination))
        ty (double (:y destination))
        tz (double (:z destination))
        dmg (damage-amount exp)
        radius (explosion-radius exp)]
    ;; Damage all living entities in 10-block radius (excluding caster)
    (when world-effects/*world-effects*
      (let [entities (world-effects/find-entities-in-radius
                       world-effects/*world-effects* world-id tx ty tz (cfg-double :combat.damage-radius))]
        (doseq [entity entities]
          (when-not (= (:uuid entity) player-id)
            (when entity-damage/*entity-damage*
              ;; TODO: use apply-damage-bypass-immunity! once protocol is extended (Bug 4)
              (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                  world-id
                                                  (:uuid entity)
                                                  dmg
                                                  :explosion))))))
    ;; Create Minecraft explosion (no fire, destroys terrain)
    (when world-effects/*world-effects*
      (world-effects/create-explosion! world-effects/*world-effects*
                                       world-id tx ty tz radius false))
    ;; Note: Experience is now granted in key-up (on fire), not here
    (log/info "PlasmaCannon: Exploded at" [tx ty tz]
              "radius:" (int radius) "damage:" (int dmg))))

;; ============================================================
;; Raycast target resolution (original: getLookingPos, max 100, living)
;; ============================================================

(defn- resolve-destination [player-id world-id player-pos]
  ;; Raycast from player eye in look direction (max 100 blocks, prefer living).
  (let [eye-x (double (:x player-pos))
      eye-y (+ (double (:y player-pos)) (cfg-double :targeting.eye-height))
      eye-z (double (:z player-pos))
      max-distance (cfg-double :targeting.raycast-distance)]
    (if raycast/*raycast*
      (let [look (raycast/get-player-look-vector raycast/*raycast* player-id)
            dx (double (or (:x look) 0.0))
            dy (double (or (:y look) 0.0))
            dz (double (or (:z look) 1.0))
            ;; Combined trace: living entities + blocks, 100 blocks
            hit (raycast/raycast-combined raycast/*raycast*
                                          world-id
                                          eye-x eye-y eye-z
                                          dx dy dz max-distance)]
        (if hit
          {:x (double (or (:x hit) eye-x))
           :y (double (or (:y hit) eye-y))
           :z (double (or (:z hit) eye-z))}
          ;; No hit: position at max distance along look vector
          {:x (+ eye-x (* dx max-distance))
           :y (+ eye-y (* dy max-distance))
           :z (+ eye-z (* dz max-distance))}))
      ;; Fallback if raycast not available
      {:x eye-x :y eye-y :z eye-z})))

;; ============================================================
;; Key handlers
;; ============================================================

(defn plasma-cannon-on-key-down
  "Server-side: initialize charge. Consume initial overload (500→400 by exp).
  Equivalent to s_madeAlive() in original."
  [{:keys [ctx-id player-id cost-ok?]}]
  (try
    (let [exp (get-skill-exp player-id)
          ct  (int (charge-time exp))]
      (if-not cost-ok?
        (do
          (fx/send-end! ctx-id :plasma-cannon/fx-end {:performed? false})
          (ctx/terminate-context! ctx-id nil)
          (log/debug "PlasmaCannon: Not enough resources to activate"))
        (let [pos      (get-player-position player-id)
              spawn-pos {:x (double (:x pos))
                         :y (+ (double (:y pos)) (cfg-double :projectile.spawn-y-offset))
                         :z (double (:z pos))}]
          (ctx/update-context! ctx-id assoc :skill-state
                               {:state        :charging
                                :charge-ticks  0
                                :charge-time   ct
                                :overload-keep (overload-keep exp)
                                :sync-ticks    0
                                :flight-ticks  0
                                :charge-pos    spawn-pos
                                :destination   nil})
          (fx/send-start! ctx-id :plasma-cannon/fx-start)
          (fx/send-update! ctx-id :plasma-cannon/fx-update
                           {:state :charging :charge-pos spawn-pos :charge-ticks 0 :fully-charged? false})
          (log/debug "PlasmaCannon: Charge started, need" ct "ticks"))))
    (catch Exception e
      (log/warn "PlasmaCannon key-down failed:" (ex-message e)))))

(defn plasma-cannon-on-key-tick
  "Server-side tick. Handles both :charging and :go states."
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            state       (or (:state skill-state) :charging)
        ov-keep     (double (or (:overload-keep skill-state) (overload-keep 1.0)))]

        ;; Always maintain overload floor (prevent recovery below overload-keep)
        (maintain-overload! player-id ov-keep)

        (case state
          :charging
          (let [charge-ticks (long (or (:charge-ticks skill-state) 0))
                charge-time  (long (or (:charge-time skill-state) (charge-time (get-skill-exp player-id))))
                next-ticks   (inc charge-ticks)
                exp          (get-skill-exp player-id)
                cp-amount    (cp-per-tick exp)
                {:keys [success?]} (skill-effects/perform-resource! player-id 0.0 cp-amount)]
            (if-not success?
              ;; Out of CP: abort (original: terminate())
              (do
                (fx/send-end! ctx-id :plasma-cannon/fx-end {:performed? false})
                (ctx/terminate-context! ctx-id nil)
                (log/debug "PlasmaCannon: Ran out of CP, aborting"))
              ;; Still charging
              (do
                (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] next-ticks)
                ;; Notify client: fully-charged flag triggers plasma_cannon_t sound
                (let [fully-charged? (>= next-ticks charge-time)]
                  (fx/send-update! ctx-id :plasma-cannon/fx-update
                                   {:charge-ticks  next-ticks
                                    :fully-charged? fully-charged?})))))

          :go
          (let [charge-pos   (:charge-pos skill-state)
                destination  (:destination skill-state)
                flight-ticks (long (or (:flight-ticks skill-state) 0))
                sync-ticks   (long (or (:sync-ticks skill-state) 0))
                world-id     (get-world-id player-id)
                next-flight  (inc flight-ticks)]
            (when (and charge-pos destination)
              (let [[new-pos last-pos] (try-move charge-pos destination)
                    dist-to-dest       (geom/vdist new-pos destination)
                    hit-block?         (block-hit? world-id last-pos new-pos)
                    should-explode?    (or hit-block?
                                          (< dist-to-dest (cfg-double :projectile.destination-epsilon))
                                          (>= next-flight (cfg-int :projectile.max-flight-ticks)))]
                (if should-explode?
                  ;; Explode at destination
                  (let [exp (get-skill-exp player-id)]
                    (do-explode! player-id world-id destination exp)
                    (fx/send-perform! ctx-id :plasma-cannon/fx-perform {:pos destination})
                    (fx/send-end! ctx-id :plasma-cannon/fx-end {:performed? true})
                    (ctx/terminate-context! ctx-id nil))
                  ;; Still flying: move and sync every configured interval
                  (let [next-sync (if (zero? sync-ticks) (cfg-int :projectile.sync-interval-ticks) (dec sync-ticks))]
                    (ctx/update-context! ctx-id update :skill-state assoc
                                         :charge-pos   new-pos
                                         :flight-ticks next-flight
                                         :sync-ticks   next-sync)
                    (when (zero? sync-ticks)
                      (fx/send-update! ctx-id :plasma-cannon/fx-update
                                       {:charge-pos   new-pos
                                        :flight-ticks next-flight})))))))

          ;; Fallback: unknown state → terminate
          (do
            (fx/send-end! ctx-id :plasma-cannon/fx-end {:performed? false})
            (ctx/terminate-context! ctx-id nil)))))
    (catch Exception e
      (log/warn "PlasmaCannon key-tick failed:" (ex-message e)))))

(defn plasma-cannon-on-key-up
  "Server-side: key released. Fire if fully charged, else abort.
  Equivalent to l_keyUp() / s_perform() in original."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state  (:skill-state ctx-data)
            state        (or (:state skill-state) :charging)
            charge-ticks (long (or (:charge-ticks skill-state) 0))
            charge-time  (long (or (:charge-time skill-state) 60))
            exp          (get-skill-exp player-id)]

        (if (or (= state :go)
                (< charge-ticks charge-time))
          ;; Not fully charged or already flying → abort
          (when-not (= state :go)
            (fx/send-end! ctx-id :plasma-cannon/fx-end {:performed? false})
            (ctx/terminate-context! ctx-id nil)
            (log/debug "PlasmaCannon: Released before fully charged, aborting"))

          ;; Fully charged → fire (equivalent to s_perform on server)
          (let [pos      (get-player-position player-id)
                world-id (or (:world-id pos) (get-world-id player-id))
                dest     (resolve-destination player-id world-id pos)
                ;; Projectile spawns 15 blocks above player
                ;; (original: add(player.getPositionVector, Vec3d(0, 15, 0)))
                spawn-pos {:x (double (:x pos))
                           :y (+ (double (:y pos)) (cfg-double :projectile.spawn-y-offset))
                           :z (double (:z pos))}]
            ;; Grant experience on successful cast (moved from do-explode! to fire-time)
            (add-exp! player-id (cfg-double :progression.exp-use))
            ;; Set cooldown (original: ctx.setCooldown in s_perform)
            (apply-cooldown! player-id exp)
            ;; Transition to :go state
            (ctx/update-context! ctx-id update :skill-state assoc
                                 :state        :go
                                 :charge-pos   spawn-pos
                                 :destination  dest
                                 :flight-ticks 0
                                 :sync-ticks   0)
            ;; Notify client: state change to flying
            (fx/send-update! ctx-id :plasma-cannon/fx-update
                             {:state       :go
                              :charge-pos  spawn-pos
                              :destination dest})
            (log/info "PlasmaCannon: Fired → destination"
                      [(int (:x dest)) (int (:y dest)) (int (:z dest))])))))
    (catch Exception e
      (log/warn "PlasmaCannon key-up failed:" (ex-message e)))))

(defn plasma-cannon-on-key-abort
  "Clean up on abort."
  [{:keys [ctx-id]}]
  (try
    (fx/send-end! ctx-id :plasma-cannon/fx-end {:performed? false})
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "PlasmaCannon: Aborted")
    (catch Exception e
      (log/warn "PlasmaCannon key-abort failed:" (ex-message e)))))

(defskill! plasma-cannon
  :id :plasma-cannon
  :category-id :vecmanip
  :name-key "ability.skill.vecmanip.plasma_cannon"
  :description-key "ability.skill.vecmanip.plasma_cannon.desc"
  :icon "textures/abilities/vecmanip/skills/plasma_cannon.png"
  :ui-position [175 14]
  :level 5
  :controllable? false
  :ctrl-id :plasma-cannon
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 1000
  :pattern :charge-window
  :cooldown {:mode :manual}
  :cost {:down {:overload plasma-cannon-cost-down-overload}}
  :actions {:down! plasma-cannon-on-key-down
            :tick! plasma-cannon-on-key-tick
            :up! plasma-cannon-on-key-up
            :abort! plasma-cannon-on-key-abort}
  :prerequisites [{:skill-id :storm-wing :min-exp 0.0}])
