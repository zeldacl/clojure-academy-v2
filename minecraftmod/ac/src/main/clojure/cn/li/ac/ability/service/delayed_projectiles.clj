(ns cn.li.ac.ability.service.delayed-projectiles
  "Server-side delayed projectile settlement for MdBall-based skills.

  Gameplay logic remains in AC. Forge only provides tick callbacks and entity shells."
  (:require [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.effect.beam]
            [cn.li.ac.ability.service.context-mgr :as ctx-mgr]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

(defn create-delayed-projectile-runtime
  ([]
   (create-delayed-projectile-runtime {}))
  ([{:keys [pending-tasks*]
     :or {pending-tasks* (atom {})}}]
   {::runtime ::delayed-projectile-runtime
    :pending-tasks* pending-tasks*}))

(def ^:dynamic *delayed-projectile-runtime* nil)

(defonce ^:private installed-delayed-projectile-runtime
  (create-delayed-projectile-runtime))

(defn- delayed-projectile-runtime?
  [runtime]
  (and (map? runtime)
       (= ::delayed-projectile-runtime (::runtime runtime))
       (some? (:pending-tasks* runtime))))

(defn call-with-delayed-projectile-runtime
  [runtime f]
  (when-not (delayed-projectile-runtime? runtime)
    (throw (ex-info "Expected delayed projectile runtime"
                    {:value runtime})))
  (binding [*delayed-projectile-runtime* runtime]
    (f)))

(defmacro with-delayed-projectile-runtime
  [runtime & body]
  `(call-with-delayed-projectile-runtime ~runtime (fn [] ~@body)))

(defn- current-delayed-projectile-runtime
  []
  (or *delayed-projectile-runtime*
      installed-delayed-projectile-runtime))

(defn- pending-tasks-atom
  []
  (:pending-tasks* (current-delayed-projectile-runtime)))

(defn pending-tasks-snapshot
  []
  @(pending-tasks-atom))

(defn reset-pending-tasks-for-test!
  ([]
   (reset-pending-tasks-for-test! {}))
  ([snapshot]
   (reset! (pending-tasks-atom) (or snapshot {}))
   nil))

(defn clear-player-tasks!
  [player-uuid]
  (swap! (pending-tasks-atom) dissoc (str player-uuid))
  nil)

(defn clear-all-tasks!
  []
  (reset! (pending-tasks-atom) {})
  nil)

(def ^:private mdball-default-life-ticks 20)
(def ^:private mdball-settle-offset-ticks 5)
(def ^:private electron-bomb-ray-distance 15.0)

(defn default-mdball-life-ticks
  []
  mdball-default-life-ticks)

(defn mdball-near-expire-delay
  "Return delay ticks that settles on MdBall lifecycle near-expire frame.

  Default behavior matches life-5 callback semantics from upstream entities."
  ([]
   (mdball-near-expire-delay mdball-default-life-ticks))
  ([life-ticks]
   (max 1 (- (int (or life-ticks mdball-default-life-ticks))
             mdball-settle-offset-ticks))))

(defn schedule-task!
  [player-uuid delay-ticks task]
  (let [ticks (max 1 (int (or delay-ticks 1)))]
    (swap! (pending-tasks-atom) update (str player-uuid) (fnil conj [])
           (assoc task :ticks-left ticks))))

(defn schedule-electron-bomb-beam!
  [{:keys [player-id] :as task}]
  (schedule-task! player-id (:delay-ticks task) (assoc task :kind :electron-bomb-beam)))

(defn schedule-electron-missile-hit!
  [{:keys [player-id] :as task}]
  (schedule-task! player-id (:delay-ticks task) (assoc task :kind :electron-missile-hit)))

(defn schedule-scatter-bomb-beam!
  [{:keys [player-id] :as task}]
  (schedule-task! player-id (:delay-ticks task) (assoc task :kind :scatter-bomb-beam)))

(defn- beam-param
  [beam key default]
  (double (or (get beam key) default)))

(defn- run-electron-bomb-beam!
  [{:keys [player-id ctx-id world-id eye look-dir damage exp-gain]}]
  (try
    (when (and raycast/*raycast* look-dir eye)
      (let [dir (geom/vnorm {:x (double (or (:x look-dir) 0.0))
                             :y (double (or (:y look-dir) 0.0))
                             :z (double (or (:z look-dir) 0.0))})
            hit (raycast/raycast-entities raycast/*raycast*
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
            (when (and target-uuid entity-damage/*entity-damage*)
              (entity-damage/apply-direct-damage!
                entity-damage/*entity-damage*
                world-id
                target-uuid
                damage-amt
                :magic)
              (md-damage/mark-target! player-id target-uuid)
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

(defn- run-electron-missile-hit!
  [{:keys [player-id ctx-id world-id target-uuid target-pos damage on-hit!]}]
  (try
    (when (and entity-damage/*entity-damage* target-uuid)
      (entity-damage/apply-direct-damage!
        entity-damage/*entity-damage*
        world-id
        target-uuid
        (double (or damage 0.0))
        :magic)
      (when (fn? on-hit!)
        (try (on-hit! target-uuid) (catch Exception _))))
    (when target-pos
      (ctx-mgr/push-channel-to-player! player-id ctx-id :electron-missile/fx-fire target-pos)
      (ctx-mgr/push-channel-to-nearby-players! ctx-id :electron-missile/fx-fire target-pos))
    (catch Exception e
      (log/warn "Delayed ElectronMissile settle failed:" (ex-message e)))))

(defn- run-scatter-bomb-beam!
  [{:keys [player-id ctx-id world-id eye look-dir damage beam]}]
  (try
    (let [result (effect/run-op!
                   {:player-id player-id
                    :ctx-id ctx-id
                    :world-id world-id
                    :eye-pos eye
                    :look-dir look-dir}
                   [:beam {:radius (beam-param beam :radius 0.3)
                           :query-radius (beam-param beam :query-radius 20.0)
                           :step (beam-param beam :step 0.8)
                           :max-distance (beam-param beam :max-distance 25.0)
                           :visual-distance (beam-param beam :visual-distance 23.0)
                           :damage (double (or damage 0.0))
                           :damage-type :magic
                           :break-blocks? false
                           :block-energy 0.0
                           :fx-topic nil}])
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
    :electron-missile-hit (run-electron-missile-hit! task)
    :scatter-bomb-beam (run-scatter-bomb-beam! task)
    nil))

(defn tick-player!
  [player-uuid]
  (let [k (str player-uuid)
        tasks (get (pending-tasks-snapshot) k)]
    (when (seq tasks)
      (let [next-tasks (volatile! [])]
        (doseq [{:keys [ticks-left] :as task} tasks]
          (if (<= (int ticks-left) 1)
            (run-task! task)
            (vswap! next-tasks conj (update task :ticks-left dec))))
        (let [remaining @next-tasks]
          (if (seq remaining)
            (swap! (pending-tasks-atom) assoc k remaining)
            (swap! (pending-tasks-atom) dissoc k)))))))
