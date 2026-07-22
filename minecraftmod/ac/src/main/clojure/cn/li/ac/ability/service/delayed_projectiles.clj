(ns cn.li.ac.ability.service.delayed-projectiles
  "Server-side delayed projectile settlement for MdBall-based skills.

  Pending tasks live in a server-thread-confined deadline scheduler and are
  never serialized into player state."
  (:require [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.effects.beam :as beam]
            [cn.li.ac.ability.service.context-manager :as ctx-mgr]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.effects.raycast :as raycast]
            [cn.li.ac.ability.effects.damage :as entity-damage]
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
   (mdball-near-expire-delay mdball-default-life-ticks))
  ([life-ticks]
   (max 1 (- (int (or life-ticks mdball-default-life-ticks))
             mdball-settle-offset-ticks))))

(defn schedule-electron-bomb-beam!
  [{:keys [player-id] :as task}]
  (schedule-task! player-id (:delay-ticks task) (assoc task :kind :electron-bomb-beam)))

(defn schedule-scatter-bomb-beam!
  [{:keys [player-id] :as task}]
  (schedule-task! player-id (:delay-ticks task) (assoc task :kind :scatter-bomb-beam)))

(defn- beam-param
  [beam key default]
  (double (or (get beam key) default)))

(defn- run-electron-bomb-beam!
  [{:keys [player-id ctx-id world-id eye look-dir damage exp-gain]}]
  (try
    (when (and (raycast/available?) look-dir eye)
      (let [dir (geom/vnorm {:x (double (or (:x look-dir) 0.0))
                             :y (double (or (:y look-dir) 0.0))
                             :z (double (or (:z look-dir) 0.0))})
            hit (raycast/raycast-entities
                                          world-id
                                          (double (:x eye))
                                          (double (:y eye))
                                          (double (:z eye))
                                          (double (:x dir))
                                          (double (:y dir))
                                          (double (:z dir))
                                          electron-bomb-ray-distance)]
        (when hit
          (let [end-pos (geom/v+ eye (geom/v* dir electron-bomb-ray-distance))
                target-uuid (:uuid hit)
                damage-amt (double (or damage 0.0))]
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
                                                    :z (:z hit)}})
              (skill-effects/add-skill-exp! player-id :electron-bomb
                                            (double (or exp-gain 0.003))))
            (ctx-mgr/push-channel-to-player! player-id ctx-id :electron-bomb/fx-beam
              {:mode :perform
               :start eye
               :end end-pos
               :hit-distance electron-bomb-ray-distance
               :performed? true
               :target-uuid target-uuid})
            (ctx-mgr/push-channel-to-nearby-players! ctx-id :electron-bomb/fx-beam
              {:mode :perform
               :start eye
               :end end-pos
               :hit-distance electron-bomb-ray-distance
               :performed? true
               :target-uuid target-uuid})))))
    (catch Exception e
      (log/warn "Delayed ElectronBomb settle failed:" (ex-message e)))))

(defn- run-scatter-bomb-beam!
  [{:keys [player-id ctx-id world-id eye look-dir damage beam]}]
  (try
    (let [result (beam/execute-beam!
                  {:player-id player-id
                   :ctx-id ctx-id
                   :world-id world-id
                   :eye-pos eye
                   :look-dir look-dir}
                  {:radius (beam-param beam :radius 0.3)
                   :query-radius (beam-param beam :query-radius 20.0)
                   :step (beam-param beam :step 0.8)
                   :max-distance (beam-param beam :max-distance 25.0)
                   :visual-distance (beam-param beam :visual-distance 23.0)
                   :damage (double (or damage 0.0))
                   :damage-type :magic
                   :break-blocks? false
                   :block-energy 0.0
                   :fx-topic nil})
            _ (doseq [target-id (or (get-in result [:beam-result :hit-uuids]) [])]
              (md-damage/mark-target! player-id target-id {:ctx-id ctx-id}))
          visual-distance (double (or (get-in result [:beam-result :visual-distance])
                                      (beam-param beam :visual-distance 23.0)))
          end-pos (geom/v+ eye (geom/v* (geom/vnorm look-dir) visual-distance))]
      (ctx-mgr/push-channel-to-player! player-id ctx-id :scatter-bomb/fx-beam
        {:start eye
         :end end-pos
         :hit-distance visual-distance})
      (ctx-mgr/push-channel-to-nearby-players! ctx-id :scatter-bomb/fx-beam
        {:start eye
         :end end-pos
         :hit-distance visual-distance}))
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
