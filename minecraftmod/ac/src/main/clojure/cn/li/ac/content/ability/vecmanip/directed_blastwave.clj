(ns cn.li.ac.content.ability.vecmanip.directed-blastwave
  "DirectedBlastwave skill aligned with original AcademyCraft behavior.

  Key alignment points:
  - Charge window: valid release in (6, 50) ticks, auto-abort at 200 ticks
  - Release point selection by 4-block living ray trace (entity/block/miss fallback)
  - AOE entity damage in radius 3 with directed knockback impulse
  - Block breaking in a 6x6x6 scan cube with spherical distance gating
  - Break probability, hardness cap, drops, damage, costs and cooldown scale by exp
  - Client wave burst visuals and directed blast sound on perform

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.block-manipulation :as block-manip]
            [cn.li.mcmod.util.log :as log]))

(def ^:private MIN-TICKS 6)
(def ^:private MAX-ACCEPTED-TICKS 50)
(def ^:private MAX-TOLERANT-TICKS 200)
(def ^:private RAYCAST-DISTANCE 4.0)
(def ^:private AOE-RADIUS 3.0)
(def ^:private PUNCH-ANIM-TICKS 6)

(defn- lerp [a b t]
  (+ (double a) (* (- (double b) (double a)) (double t))))

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (double x))))

(defn- v+ [a b]
  {:x (+ (double (:x a)) (double (:x b)))
   :y (+ (double (:y a)) (double (:y b)))
   :z (+ (double (:z a)) (double (:z b)))})

(defn- v- [a b]
  {:x (- (double (:x a)) (double (:x b)))
   :y (- (double (:y a)) (double (:y b)))
   :z (- (double (:z a)) (double (:z b)))})

(defn- v* [v s]
  {:x (* (double (:x v)) (double s))
   :y (* (double (:y v)) (double s))
   :z (* (double (:z v)) (double s))})

(defn- vlen [v]
  (Math/sqrt (+ (* (:x v) (:x v))
                (* (:y v) (:y v))
                (* (:z v) (:z v)))))

(defn- normalize [v]
  (let [len (max 1.0e-6 (vlen v))]
    (v* v (/ 1.0 len))))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :directed-blastwave :exp] 0.0)))

(defn- player-pos [player-id]
  (get (ps/get-player-state player-id)
       :position
       {:world-id "minecraft:overworld"
        :x 0.0 :y 64.0 :z 0.0}))

(defn- player-world-id [player-id]
  (or (get-in (ps/get-player-state player-id) [:position :world-id])
      "minecraft:overworld"))

(defn- eye-pos [pos]
  {:x (double (:x pos))
   :y (+ (double (:y pos)) 1.62)
   :z (double (:z pos))})

(defn- cp-cost [exp] (lerp 160.0 200.0 exp))
(defn- overload-cost [exp] (lerp 50.0 30.0 exp))
(defn- cooldown-ticks [exp] (int (lerp 80.0 50.0 exp)))
(defn- break-prob [exp] (lerp 0.5 0.8 exp))
(defn- damage-value [exp] (lerp 10.0 25.0 exp))
(defn- drop-rate [exp] (lerp 0.4 0.9 exp))

(defn- break-hardness [exp]
  (cond
    (< (double exp) 0.25) 2.9
    (< (double exp) 0.5) 25.0
    :else 55.0))

(defn- add-exp! [player-id amount]
  (when-let [state (ps/get-player-state player-id)]
    (let [{:keys [data events]} (learning/add-skill-exp
                                  (:ability-data state)
                                  player-id
                                  :directed-blastwave
                                  (double amount)
                                  1.0)]
      (ps/update-ability-data! player-id (constantly data))
      (doseq [e events]
        (ability-evt/fire-ability-event! e)))))

(defn directed-blastwave-cost-up-cp
  [{:keys [player-id]}]
  (cp-cost (clamp01 (get-skill-exp player-id))))

(defn directed-blastwave-cost-up-overload
  [{:keys [player-id]}]
  (overload-cost (clamp01 (get-skill-exp player-id))))

(defn- apply-cooldown! [player-id exp]
  (ps/update-cooldown-data! player-id cd/set-main-cooldown :directed-blastwave (max 1 (cooldown-ticks exp))))

(defn- hit-position-from-trace [player-id trace]
  (let [pos (player-pos player-id)
        eye (eye-pos pos)
        look (or (when raycast/*raycast*
                   (raycast/get-player-look-vector raycast/*raycast* player-id))
                 {:x 0.0 :y 0.0 :z 1.0})]
    (cond
      (nil? trace)
      (v+ eye (v* (normalize look) RAYCAST-DISTANCE))

      (= :block (:hit-type trace))
      {:x (Math/floor (double (or (:x trace) 0.0)))
       :y (Math/floor (double (or (:y trace) 0.0)))
       :z (Math/floor (double (or (:z trace) 0.0)))}

      (= :entity (:hit-type trace))
      {:x (double (or (:x trace) 0.0))
       :y (+ (double (or (:y trace) 0.0)) (double (or (:eye-height trace) 1.62)))
       :z (double (or (:z trace) 0.0))}

      :else
      {:x (double (or (:x trace) 0.0))
       :y (double (or (:y trace) 0.0))
       :z (double (or (:z trace) 0.0))})))

(defn- knockback-impulse [caster-pos entity]
  (let [player-head (eye-pos caster-pos)
        target-head {:x (double (or (:x entity) 0.0))
                     :y (+ (double (or (:y entity) 0.0)) (double (or (:eye-height entity) 1.62)))
                     :z (double (or (:z entity) 0.0))}
        d0 (normalize (v- player-head target-head))
        d1 (normalize {:x (:x d0)
                       :y (- (:y d0) 0.4)
                       :z (:z d0)})]
    (v* d1 -1.2)))

(defn- break-nearby-blocks! [player-id world-id pos exp]
  (when block-manip/*block-manipulation*
    (let [x0 (int (Math/floor (double (:x pos))))
          y0 (int (Math/floor (double (:y pos))))
          z0 (int (Math/floor (double (:z pos))))
          hardness-cap (break-hardness exp)
          p-break (break-prob exp)
          p-drop (drop-rate exp)
          full-exp? (= 1.0 (double (clamp01 exp)))]
      (doseq [x (range (- x0 3) (+ x0 3))
              y (range (- y0 3) (+ y0 3))
              z (range (- z0 3) (+ z0 3))]
        (let [dx (- x x0)
              dy (- y y0)
              dz (- z z0)
              dist-sq (+ (* dx dx) (* dy dy) (* dz dz))]
          (when (and (<= dist-sq 6)
                     (or (zero? dist-sq) (< (rand) p-break)))
            (let [hardness (block-manip/get-block-hardness block-manip/*block-manipulation* world-id x y z)
                  block-id (block-manip/get-block block-manip/*block-manipulation* world-id x y z)
                  breakable? (and (number? hardness)
                                  (>= (double hardness) 0.0)
                                  (<= (double hardness) (double hardness-cap))
                                  (some? block-id)
                                  (block-manip/can-break-block? block-manip/*block-manipulation* player-id world-id x y z))]
              (when breakable?
                (if full-exp?
                  (block-manip/break-block! block-manip/*block-manipulation* player-id world-id x y z true)
                  (block-manip/break-block! block-manip/*block-manipulation* player-id world-id x y z (< (rand) p-drop)))))))))))

(defn- send-fx-start! [ctx-id]
  (ctx/ctx-send-to-client! ctx-id :directed-blastwave/fx-start {:mode :start}))

(defn- send-fx-update! [ctx-id charge-ticks punched?]
  (ctx/ctx-send-to-client! ctx-id :directed-blastwave/fx-update
                           {:mode :update
                            :charge-ticks (long (max 0 charge-ticks))
                            :punched? (boolean punched?)}))

(defn- send-fx-perform! [ctx-id hit-pos charge-ticks look]
  (ctx/ctx-send-to-client! ctx-id :directed-blastwave/fx-perform
                           {:mode :perform
                            :pos hit-pos
                            :look-dir (or look {:x 0.0 :y 0.0 :z 1.0})
                            :charge-ticks (long (max 0 charge-ticks))}))

(defn- send-fx-end! [ctx-id performed?]
  (ctx/ctx-send-to-client! ctx-id :directed-blastwave/fx-end
                           {:mode :end
                            :performed? (boolean performed?)}))

(defn directed-blastwave-on-key-down [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id assoc :skill-state
                         {:charge-ticks 0
                          :punched? false
                          :punch-ticks 0
                          :performed? false})
    (send-fx-start! ctx-id)
    (send-fx-update! ctx-id 0 false)
    (log/debug "DirectedBlastwave charge started")
    (catch Exception e
      (log/warn "DirectedBlastwave key-down failed:" (ex-message e)))))

(defn directed-blastwave-on-key-tick [{:keys [ctx-id]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (long (or (:charge-ticks skill-state) 0))
            punched? (boolean (:punched? skill-state))
            punch-ticks (long (or (:punch-ticks skill-state) 0))
            next-charge (inc charge-ticks)
            next-punch (if punched? (inc punch-ticks) 0)]
        (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] next-charge)
        (ctx/update-context! ctx-id assoc-in [:skill-state :punch-ticks] next-punch)
        (send-fx-update! ctx-id next-charge punched?)

        (cond
          (>= next-charge MAX-TOLERANT-TICKS)
          (do
            (send-fx-end! ctx-id false)
            (ctx/terminate-context! ctx-id nil))

          (and punched? (> next-punch PUNCH-ANIM-TICKS))
          (do
            (send-fx-end! ctx-id true)
            (ctx/terminate-context! ctx-id nil))

          :else nil)))
    (catch Exception e
      (log/warn "DirectedBlastwave key-tick failed:" (ex-message e)))))

(defn directed-blastwave-on-key-up [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (when-let [ctx-data (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx-data)
            charge-ticks (long (or (:charge-ticks skill-state) 0))]
        (if (and (> charge-ticks MIN-TICKS) (< charge-ticks MAX-ACCEPTED-TICKS))
          (let [exp (clamp01 (get-skill-exp player-id))
                world-id (player-world-id player-id)]
            (if-not cost-ok?
              (do
                (send-fx-end! ctx-id false)
                (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false))
              (let [trace (when raycast/*raycast*
                            (raycast/raycast-from-player raycast/*raycast* player-id RAYCAST-DISTANCE true))
                    hit-pos (hit-position-from-trace player-id trace)
                    look (or (when raycast/*raycast*
                               (raycast/get-player-look-vector raycast/*raycast* player-id))
                             {:x 0.0 :y 0.0 :z 1.0})
                    entities (if world-effects/*world-effects*
                               (->> (world-effects/find-entities-in-radius
                                      world-effects/*world-effects*
                                      world-id
                                      (:x hit-pos) (:y hit-pos) (:z hit-pos)
                                      AOE-RADIUS)
                                    (remove #(= (:uuid %) player-id))
                                    vec)
                               [])
                    caster-pos (player-pos player-id)
                    damage (damage-value exp)]

                (doseq [entity entities]
                  (when entity-damage/*entity-damage*
                    (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                        world-id
                                                        (:uuid entity)
                                                        damage
                                                        :generic))
                  (let [impulse (knockback-impulse caster-pos entity)]
                    (when entity-motion/*entity-motion*
                      (entity-motion/add-velocity! entity-motion/*entity-motion*
                                                   world-id
                                                   (:uuid entity)
                                                   (:x impulse)
                                                   (:y impulse)
                                                   (:z impulse)))))

                (break-nearby-blocks! player-id world-id hit-pos exp)
                (send-fx-perform! ctx-id hit-pos charge-ticks look)
                (ctx/update-context! ctx-id update :skill-state assoc
                                     :punched? true
                                     :punch-ticks 0
                                     :performed? true)
                (apply-cooldown! player-id exp)
                (add-exp! player-id (if (seq entities) 0.0025 0.0012))
                (log/info "DirectedBlastwave executed"
                          "charge" charge-ticks
                          "entities" (count entities)))))

          (do
            (send-fx-end! ctx-id false)
            (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false)
            (log/debug "DirectedBlastwave invalid charge" charge-ticks)))))
    (catch Exception e
      (log/warn "DirectedBlastwave key-up failed:" (ex-message e)))))

(defn directed-blastwave-on-key-abort [{:keys [ctx-id]}]
  (try
    (send-fx-end! ctx-id false)
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "DirectedBlastwave aborted")
    (catch Exception e
      (log/warn "DirectedBlastwave key-abort failed:" (ex-message e)))))
