(ns cn.li.ac.ability.service.delayed-projectiles
  "Server-side delayed projectile settlement for MdBall-based skills.

  Pending tasks live in player-state [:runtime :delayed-projectiles :pending-tasks]."
  (:require [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.effects.beam :as beam]
            [cn.li.ac.ability.service.context-manager :as ctx-mgr]
            [cn.li.ac.ability.service.player-runtime-commands :as prt-cmd]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

(def ^:private mdball-default-life-ticks 20)
(def ^:private mdball-settle-offset-ticks 5)
(def ^:private electron-bomb-ray-distance 15.0)

(defn pending-tasks-snapshot
  [player-uuid]
  (prt-cmd/pending-delayed-tasks player-uuid))

(defn clear-player-tasks!
  [player-uuid]
  (prt-cmd/run-for-player!
   player-uuid
   {:command :clear-delayed-projectile-tasks})
  nil)

(defn clear-all-tasks!
  []
  (let [session-id (prt-cmd/session-id)]
    (doseq [player-uuid (store/list-players session-id)]
      (clear-player-tasks! player-uuid)))
  nil)

(defn schedule-task!
  [player-uuid delay-ticks task]
  (prt-cmd/run-for-player!
   player-uuid
   {:command :schedule-delayed-projectile-task
    :delay-ticks delay-ticks
    :task task})
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
            hit (raycast/raycast-entities*
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
              (entity-damage/apply-direct-damage!*
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
  "Drive delayed projectile tasks for one player once per server tick.
   Optimized: skips the :tick-delayed-projectile-tasks command dispatch
   entirely when the player has no pending tasks (the common idle case)."
  [player-uuid]
  (when (seq (prt-cmd/pending-delayed-tasks player-uuid))
    (let [result (prt-cmd/run-for-player!
                  player-uuid
                  {:command :tick-delayed-projectile-tasks})
          events (:events result)]
      (when (not-empty events)
        (doseq [{:keys [task]} events]
          (run-task! task)))))
  nil)
