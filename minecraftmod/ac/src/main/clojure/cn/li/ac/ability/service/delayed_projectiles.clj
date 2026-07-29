(ns cn.li.ac.ability.service.delayed-projectiles
  "Server-side delayed projectile settlement for MdBall-based skills.

  Pending tasks live in a server-thread-confined deadline scheduler and are
  never serialized into player state."
  (:require [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.service.context-manager :as ctx-mgr]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log])
  (:import [java.util ArrayList HashMap]))

(def ^:private mdball-default-life-ticks 20)
(def ^:private mdball-settle-offset-ticks 5)
(def ^:private electron-bomb-ray-distance 15.0)

(definterface IScheduledProjectile
  (^long dueTick [])
  (^Object taskValue []))

(deftype ScheduledProjectile [^long due task]
  IScheduledProjectile
  (dueTick [_] due)
  (taskValue [_] task))

(definterface IPlayerProjectileSchedule
  (^java.util.ArrayList scheduledTasks [])
  (^longs currentTick []))

(deftype PlayerProjectileSchedule [^ArrayList tasks ^longs tick]
  IPlayerProjectileSchedule
  (scheduledTasks [_] tasks)
  (currentTick [_] tick))

(defonce ^:private ^HashMap schedules-by-player (HashMap.))

(defn- player-schedule
  ^PlayerProjectileSchedule [player-uuid create?]
  (or (.get schedules-by-player player-uuid)
      (when create?
        (let [schedule (PlayerProjectileSchedule. (ArrayList.) (long-array 1))]
          (.put schedules-by-player player-uuid schedule)
          schedule))))

(defn pending-tasks-snapshot
  [player-uuid]
  (if-let [^PlayerProjectileSchedule schedule (player-schedule player-uuid false)]
    (let [^ArrayList tasks (.scheduledTasks schedule)
          now (aget (.currentTick schedule) 0)]
      (mapv (fn [^ScheduledProjectile scheduled]
              (assoc (.taskValue scheduled)
                     :ticks-left (max 0 (- (.dueTick scheduled) now))))
            tasks))
    []))

(defn clear-player-tasks!
  [player-uuid]
  (.remove schedules-by-player player-uuid)
  nil)

(defn clear-all-tasks!
  []
  (.clear schedules-by-player)
  nil)

(defn schedule-task!
  [player-uuid delay-ticks task]
  (let [^PlayerProjectileSchedule schedule (player-schedule player-uuid true)
        now (aget (.currentTick schedule) 0)
        due (+ now (max 1 (long (or delay-ticks 1))))]
    (.add (.scheduledTasks schedule) (ScheduledProjectile. due task)))
  nil)

(defn reset-pending-tasks-for-test!
  ([]
   (reset-pending-tasks-for-test! {}))
  ([snapshot]
   (clear-all-tasks!)
   (doseq [[player-uuid tasks] snapshot
           task tasks]
     (doseq [t tasks]
       (schedule-task! player-uuid (:ticks-left t) (dissoc t :ticks-left))))
   nil))

(defn default-mdball-life-ticks
  []
  mdball-default-life-ticks)

(defn mdball-near-expire-delay
  ([]
   (mdball-near-expire-delay mdball-default-life-ticks mdball-settle-offset-ticks))
  ([life-ticks]
   (mdball-near-expire-delay life-ticks mdball-settle-offset-ticks))
  ([life-ticks offset-ticks]
   (max 1 (- (int (or life-ticks mdball-default-life-ticks))
             (int (or offset-ticks mdball-settle-offset-ticks))))))

(defn schedule-electron-bomb-beam!
  [{:keys [player-id] :as task}]
  (schedule-task! player-id (:delay-ticks task) (assoc task :kind :electron-bomb-beam)))

(defn schedule-scatter-bomb-beam!
  [{:keys [player-id] :as task}]
  (schedule-task! player-id (:delay-ticks task) (assoc task :kind :scatter-bomb-beam)))

(defn- run-electron-bomb-beam!
  "Matches original's callback: getDest(player)/eye are re-evaluated fresh at
  settle time (not the values captured when the skill was cast), so the
  player can turn or move during the delay and still redirect the shot."
  [{:keys [player-id ctx-id damage]}]
  (try
    (when (raycast/available?)
      (let [world-id (geom/world-id-of player-id)
            eye (geom/eye-pos player-id)
            look-vec (raycast/player-look-vector player-id)]
        (when look-vec
          (let [dir (geom/vnorm {:x (double (or (:x look-vec) 0.0))
                                 :y (double (or (:y look-vec) 0.0))
                                 :z (double (or (:z look-vec) 0.0))})
                hit (raycast/raycast-entities
                                              world-id
                                              (double (:x eye))
                                              (double (:y eye))
                                              (double (:z eye))
                                              (double (:x dir))
                                              (double (:y dir))
                                              (double (:z dir))
                                              electron-bomb-ray-distance)
                end-pos (geom/v+ eye (geom/v* dir electron-bomb-ray-distance))
                target-uuid (:uuid hit)
                damage-amt (double (or damage 0.0))
                payload {:mode :perform
                         :start eye
                         :end end-pos
                         :hit-distance electron-bomb-ray-distance
                         :performed? true
                         :target-uuid target-uuid}]
            (when (and target-uuid (entity-damage/available?))
              (entity-damage/apply-direct-damage!
                world-id
                target-uuid
                damage-amt
                :magic)
              (md-damage/mark-target! player-id target-uuid
                                      {:ctx-id ctx-id
                                       :target-pos {:x (:x hit)
                                                    :y (:y hit)
                                                    :z (:z hit)}}))
            ;; Original broadcasts the small ray even when it hits no entity.
            (ctx-mgr/push-channel-to-player! player-id ctx-id :electron-bomb/fx-beam payload)
            (ctx-mgr/push-channel-to-nearby-players! ctx-id :electron-bomb/fx-beam payload)))))
    (catch Exception e
      (log/warn "Delayed ElectronBomb settle failed:" (ex-message e)))))

(defn- run-scatter-bomb-beam!
  "Matches original's per-ball resolution: a precise point-to-point raycast
  from the ball's (approximated) origin to its independently-randomized
  destination, single-entity hit, with hurtResistantTime=-1 (reset-
  invulnerable-time?) so multiple balls can all connect on the same target
  within one release — not a radius/falloff beam."
  [{:keys [player-id ctx-id world-id origin dest damage]}]
  (try
    (when (raycast/available?)
      (let [ox (double (:x origin)) oy (double (:y origin)) oz (double (:z origin))
            dx (- (double (:x dest)) ox)
            dy (- (double (:y dest)) oy)
            dz (- (double (:z dest)) oz)
            dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
            dir  (if (pos? dist)
                   {:x (/ dx dist) :y (/ dy dist) :z (/ dz dist)}
                   {:x 0.0 :y 0.0 :z 1.0})
            hit  (raycast/raycast-entities
                   world-id ox oy oz
                   (:x dir) (:y dir) (:z dir)
                   (max 0.1 dist))
            target-uuid (:uuid hit)]
        (when (and target-uuid (entity-damage/available?))
          (entity-damage/apply-direct-damage!
            world-id target-uuid (double (or damage 0.0)) :magic
            {:reset-invulnerable-time? true})
          (md-damage/mark-target! player-id target-uuid {:ctx-id ctx-id}))
        (ctx-mgr/push-channel-to-player! player-id ctx-id :scatter-bomb/fx-beam
          {:start origin :end dest})
        (ctx-mgr/push-channel-to-nearby-players! ctx-id :scatter-bomb/fx-beam
          {:start origin :end dest})))
    (catch Exception e
      (log/warn "Delayed ScatterBomb settle failed:" (ex-message e)))))

(defn- run-task!
  [{:keys [kind] :as task}]
  (case kind
    :electron-bomb-beam (run-electron-bomb-beam! task)
    :scatter-bomb-beam (run-scatter-bomb-beam! task)
    nil))

(defn tick-player!
  "Advance one player's deadline queue. Idle players do one HashMap lookup;
  active queues mutate in place and allocate no due/remaining collections."
  [player-uuid]
  (when-let [^PlayerProjectileSchedule schedule (player-schedule player-uuid false)]
    (let [^longs tick-cell (.currentTick schedule)
          now (unchecked-inc (aget tick-cell 0))
          ^ArrayList tasks (.scheduledTasks schedule)]
      (aset-long tick-cell 0 now)
      (loop [i 0]
        (when (< i (.size tasks))
          (let [^ScheduledProjectile scheduled (.get tasks i)]
            (if (<= (.dueTick scheduled) now)
              (do
                (.remove tasks (int i))
                (run-task! (.taskValue scheduled))
                (recur i))
              (recur (unchecked-inc-int i))))))
      (when (.isEmpty tasks)
        (.remove schedules-by-player player-uuid))))
  nil)
