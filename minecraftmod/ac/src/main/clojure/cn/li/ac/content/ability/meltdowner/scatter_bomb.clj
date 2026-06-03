(ns cn.li.ac.content.ability.meltdowner.scatter-bomb
  "ScatterBomb skill - hold to accumulate balls, release for scatter shot.

  Pattern: :hold-channel
  Down cost: overload lerp(150, 120, exp)
  Tick cost: CP lerp(8, 5, exp) per tick
  Ball spawn: 1 ball every 10 ticks from tick 20 (max 6 balls)
  On release: each ball fires a beam in random cone direction
  Anti-AFK: at tick 200, apply 6 self-damage
  Overload floor: enforced during hold
  Cooldown: lerp(30, 15, exp) 脳 ball-count ticks
  Exp: +0.002 脳 ball-count

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.delayed-projectiles :as delayed-projectiles]
            [cn.li.ac.ability.effects.geom :as geom]
                        [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

(def ^:private mdball-entity-id "my_mod:entity_md_ball")
(def ^:private scatter-bomb-skill-id :scatter-bomb)

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id scatter-bomb-skill-id))

(defn- cfg-double [field-id]
  (skill-config/tunable-double scatter-bomb-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int scatter-bomb-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double scatter-bomb-skill-id field-id exp))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int scatter-bomb-skill-id field-id exp))

(defn- current-hold-ticks
  [ctx-id]
  (long (or (get-in (ctx/get-context ctx-id) [:skill-state :hold-ticks]) 0)))

(defn- set-skill-state!
  [ctx-id k v]
  (ctx-skill/assoc-skill-state! ctx-id k v))

(defn- set-skill-state-root!
  [ctx-id state-map]
  (ctx-skill/update-skill-state-root! ctx-id identity state-map))

(defn- beam-config []
  {:radius          (cfg-double :beam.radius)
   :query-radius    (cfg-double :beam.query-radius)
   :step            (cfg-double :beam.step)
   :max-distance    (cfg-double :beam.max-distance)
   :visual-distance (cfg-double :beam.visual-distance)})

(defn- enforce-overload-floor!
  [player-id floor-value]
  (skill-effects/enforce-overload-floor! player-id floor-value))

(defn- random-cone-dir
  "Random direction within 卤45掳 cone of look direction."
  [look-vec]
  (let [[spread-min spread-max] (skill-config/tunable-double-list scatter-bomb-skill-id
                                                                  :projectile.cone-spread)
        spread (+ spread-min (rand (max 0.0 (- spread-max spread-min))))
        rx (* spread (- (rand 1.0) 0.5))
        ry (* spread (- (rand 1.0) 0.5))
        rz (* spread (- (rand 1.0) 0.5))
        dx (+ (double (:x look-vec)) rx)
        dy (+ (double (:y look-vec)) ry)
        dz (+ (double (:z look-vec)) rz)
        len (max 1.0e-6 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))]
    {:x (/ dx len) :y (/ dy len) :z (/ dz len)}))

(def ^:dynamic *scatter-direction-sampler*
  "Injectable sampler seam for deterministic tests."
  random-cone-dir)

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn scatter-bomb-down!
  [{:keys [player-id ctx-id cost-ok?]}]
  (when cost-ok?
    (let [exp (skill-exp player-id)
          floor (* (cfg-lerp :cost.down.overload exp)
                   (cfg-double :cost.overload-floor-scale))]
    (set-skill-state-root! ctx-id
               {:balls        0
            :hold-ticks   0
            :overload-floor floor})
      (ctx/ctx-send-to-client! ctx-id :scatter-bomb/fx-start {}))))

(defn scatter-bomb-tick!
  [{:keys [player-id ctx-id player]}]
  (let [ctx-data (ctx/get-context ctx-id)]
    (when ctx-data
      (let [ticks (inc (long (or (get-in ctx-data [:skill-state :hold-ticks]) 0)))
            _ (set-skill-state! ctx-id [:hold-ticks] ticks)
            balls (int (or (get-in ctx-data [:skill-state :balls]) 0))
            floor (double (or (get-in ctx-data [:skill-state :overload-floor]) 0.0))]
        ;; Enforce overload floor
        (enforce-overload-floor! player-id floor)
        ;; Anti-AFK self-damage at tick 200
        (when (= ticks (cfg-int :effect.anti-afk-tick))
          (when (entity-damage/available?)
            (entity-damage/apply-direct-damage!*
              (geom/world-id-of player-id)
              player-id
              (cfg-double :effect.anti-afk-damage)
              :magic))
          (ctx/ctx-send-to-client! ctx-id :scatter-bomb/fx-end {:balls balls})
          (ctx/terminate-context! ctx-id nil))
        ;; Spawn new ball every N ticks
        (when (and (<= ticks (cfg-int :projectile.max-hold-ticks))
                   (>= ticks (cfg-int :projectile.spawn-start-tick))
                   (< balls (cfg-int :projectile.max-balls))
                   (zero? (mod (- ticks (cfg-int :projectile.spawn-start-tick))
                               (cfg-int :projectile.spawn-interval-ticks))))
          (let [new-balls (inc balls)]
            (set-skill-state! ctx-id [:balls] new-balls)
            (when player
              (entity/player-spawn-entity-by-id! player mdball-entity-id 0.0))
            (let [eye (geom/eye-pos player-id)]
              (ctx/ctx-send-to-client! ctx-id :scatter-bomb/fx-ball
                                       {:x (:x eye) :y (:y eye) :z (:z eye)
                                        :count new-balls}))))))))

(defn scatter-bomb-up!
  [{:keys [player-id ctx-id]}]
  (let [ctx-data (ctx/get-context ctx-id)
        balls (int (or (get-in ctx-data [:skill-state :balls]) 0))
        exp (skill-exp player-id)]
    (when (pos? balls)
      (let [world-id (geom/world-id-of player-id)
            eye      (geom/eye-pos player-id)
            look-vec (when (raycast/available?)
                       (raycast/get-player-look-vector* player-id))
            damage   (cfg-lerp :combat.damage exp)]
        (when look-vec
          ;; Each ball settles with a slight delay to preserve projectile cadence.
          (let [base-delay (delayed-projectiles/mdball-near-expire-delay)]
            (dotimes [i balls]
              (let [dir (*scatter-direction-sampler* look-vec)]
                (delayed-projectiles/schedule-scatter-bomb-beam!
                  {:player-id   player-id
                   :ctx-id      ctx-id
                   :world-id    world-id
                   :eye         eye
                   :look-dir    {:x (:x dir) :y (:y dir) :z (:z dir)}
                   :damage      damage
                   :beam        (beam-config)
                   :delay-ticks (+ base-delay i)}))))
          ;; Gain exp and set cooldown
          (skill-effects/add-skill-exp! player-id scatter-bomb-skill-id
                                        (* (cfg-double :progression.exp-per-ball) balls))
          (skill-effects/set-main-cooldown!
            player-id scatter-bomb-skill-id
            (int (* balls (cfg-lerp :cooldown.ticks-per-ball exp))))
          (log/debug "ScatterBomb: fired" balls "balls"))))
    (ctx/ctx-send-to-client! ctx-id :scatter-bomb/fx-end {:balls balls})))

(defn scatter-bomb-cost-fail!
  [{:keys [ctx-id cost-stage]}]
  (when (= cost-stage :tick)
    (let [balls (int (or (get-in (ctx/get-context ctx-id) [:skill-state :balls]) 0))]
      (ctx/ctx-send-to-client! ctx-id :scatter-bomb/fx-end {:balls balls}))))

(defn scatter-bomb-abort!
  [{:keys [ctx-id]}]
  (ctx/ctx-send-to-client! ctx-id :scatter-bomb/fx-end {:balls 0}))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill scatter-bomb
  :id             :scatter-bomb
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.scatter_bomb"
  :description-key "ability.skill.meltdowner.scatter_bomb.desc"
  :icon           "textures/abilities/meltdowner/skills/scatter_bomb.png"
  :ui-position    [100 140]
  :level          2
  :controllable?  true
  :ctrl-id        :scatter-bomb
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :cooldown-ticks 1
  :pattern        :hold-channel
  :cooldown       {:mode :manual}
  :cost           {:down {:overload (fn [{:keys [player-id]}]
                (cfg-lerp :cost.down.overload (skill-exp player-id)))}
                   :tick {:cp (fn [{:keys [player-id ctx-id]}]
                                (let [ticks (current-hold-ticks ctx-id)]
                                  (if (<= ticks (cfg-int :projectile.max-hold-ticks))
                                    (cfg-lerp :cost.tick.cp (skill-exp player-id))
                                    0.0)))} }
  :actions        {:down!  scatter-bomb-down!
                   :tick!  scatter-bomb-tick!
                   :up!    scatter-bomb-up!
                   :abort! scatter-bomb-abort!
                   :cost-fail! scatter-bomb-cost-fail!}
  :prerequisites  [{:skill-id :electron-bomb :min-exp 0.8}])

