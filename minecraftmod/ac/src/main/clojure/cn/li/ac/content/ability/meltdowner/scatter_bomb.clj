(ns cn.li.ac.content.ability.meltdowner.scatter-bomb
  "ScatterBomb skill - hold to accumulate balls, release for scatter shot.

  Pattern: :hold-channel
  Down cost: overload lerp(150, 120, exp)
  Tick cost: CP lerp(8, 5, exp) per tick
  Ball spawn: 1 ball every 10 ticks from tick 20 (max 6 balls)
  On release: each ball fires a beam in random cone direction
  Anti-AFK: at tick 200, apply 6 self-damage
  Overload floor: enforced during hold
  Cooldown: lerp(30, 15, exp) × ball-count ticks
  Exp: +0.002 × ball-count

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.effect.beam]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private max-balls 6)
(def ^:private ball-spawn-interval 10)
(def ^:private ball-spawn-start-tick 20)
(def ^:private anti-afk-tick 200)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id)
                  [:ability-data :skills :scatter-bomb :exp]
                  0.0)))

(defn- enforce-overload-floor!
  [player-id floor-value]
  (ps/update-resource-data!
    player-id
    (fn [res-data]
      (if (< (double (get res-data :cur-overload 0.0)) (double floor-value))
        (-> res-data
            (rdata/set-cur-overload floor-value)
            (assoc :overload-fine true))
        res-data))))

(defn- random-cone-dir
  "Random direction within ±45° cone of look direction."
  [look-vec]
  (let [spread (+ 0.3 (rand 0.5))
        rx (* spread (- (rand 1.0) 0.5))
        ry (* spread (- (rand 1.0) 0.5))
        rz (* spread (- (rand 1.0) 0.5))
        dx (+ (double (:x look-vec)) rx)
        dy (+ (double (:y look-vec)) ry)
        dz (+ (double (:z look-vec)) rz)
        len (max 1.0e-6 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))]
    {:x (/ dx len) :y (/ dy len) :z (/ dz len)}))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn scatter-bomb-down!
  [{:keys [player-id ctx-id cost-ok?]}]
  (when cost-ok?
    (let [exp (skill-exp player-id)
          floor (* (bal/lerp 150.0 120.0 exp) 0.8)]
      (ctx/update-context! ctx-id assoc :skill-state
                           {:balls        0
                            :hold-ticks   0
                            :overload-floor floor})
      (ctx/ctx-send-to-client! ctx-id :scatter-bomb/fx-start {}))))

(defn scatter-bomb-tick!
  [{:keys [player-id ctx-id hold-ticks]}]
  (let [ticks (long (or hold-ticks 0))
        ctx-data (ctx/get-context ctx-id)]
    (when ctx-data
      (let [exp (skill-exp player-id)
            balls (int (or (get-in ctx-data [:skill-state :balls]) 0))
            floor (double (or (get-in ctx-data [:skill-state :overload-floor]) 0.0))]
        ;; Enforce overload floor
        (enforce-overload-floor! player-id floor)
        ;; Anti-AFK self-damage at tick 200
        (when (= ticks anti-afk-tick)
          (when entity-damage/*entity-damage*
            (entity-damage/apply-direct-damage!
              entity-damage/*entity-damage*
              (geom/world-id-of player-id)
              player-id
              6.0
              :magic)))
        ;; Spawn new ball every N ticks
        (when (and (>= ticks ball-spawn-start-tick)
                   (< balls max-balls)
                   (zero? (mod (- ticks ball-spawn-start-tick) ball-spawn-interval)))
          (let [new-balls (inc balls)]
            (ctx/update-context! ctx-id assoc-in [:skill-state :balls] new-balls)
            (let [eye (geom/eye-pos player-id)]
              (ctx/ctx-send-to-client! ctx-id :scatter-bomb/fx-ball
                                       {:x (:x eye) :y (:y eye) :z (:z eye)
                                        :count new-balls}))))))))

(defn scatter-bomb-up!
  [{:keys [player-id ctx-id hold-ticks]}]
  (let [ctx-data (ctx/get-context ctx-id)
        balls (int (or (get-in ctx-data [:skill-state :balls]) 0))
        exp (skill-exp player-id)]
    (when (pos? balls)
      (let [world-id (geom/world-id-of player-id)
            eye      (geom/eye-pos player-id)
            look-vec (when raycast/*raycast*
                       (raycast/get-player-look-vector raycast/*raycast* player-id))
            damage   (bal/lerp 4.0 9.0 exp)]
        (when look-vec
          ;; Each ball fires in a random cone direction
          (dotimes [_i balls]
            (let [dir (random-cone-dir look-vec)]
              (effect/run-op!
                {:player-id  player-id
                 :ctx-id     ctx-id
                 :world-id   world-id
                 :eye-pos    eye
                 :look-dir   {:x (:x dir) :y (:y dir) :z (:z dir)}}
                [:beam {:radius          0.3
                        :query-radius    20.0
                        :step            0.8
                        :max-distance    25.0
                        :visual-distance 23.0
                        :damage          damage
                        :damage-type     :magic
                        :break-blocks?   false
                        :block-energy    0.0
                        :fx-topic        :scatter-bomb/fx-beam}])))
          ;; Gain exp and set cooldown
          (skill-effects/add-skill-exp! player-id :scatter-bomb (* 0.002 balls))
          (skill-effects/set-main-cooldown!
            player-id :scatter-bomb
            (int (* balls (bal/lerp 30.0 15.0 exp))))
          (log/debug "ScatterBomb: fired" balls "balls"))))
    (ctx/ctx-send-to-client! ctx-id :scatter-bomb/fx-end {:balls balls})))

(defn scatter-bomb-abort!
  [{:keys [ctx-id]}]
  (ctx/ctx-send-to-client! ctx-id :scatter-bomb/fx-end {:balls 0}))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! scatter-bomb
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
                                      (bal/lerp 150.0 120.0 (skill-exp player-id)))}
                   :tick {:cp (fn [{:keys [player-id]}]
                                (bal/lerp 8.0 5.0 (skill-exp player-id)))}}
  :actions        {:down!  scatter-bomb-down!
                   :tick!  scatter-bomb-tick!
                   :up!    scatter-bomb-up!
                   :abort! scatter-bomb-abort!}
  :prerequisites  [{:skill-id :electron-bomb :min-exp 0.8}])
