(ns cn.li.ac.ability.server.service.delayed-projectiles
	"Server-side delayed projectile settlement for MdBall-based skills.

	Gameplay logic remains in AC. Forge only provides tick callbacks and entity shells."
	(:require [cn.li.ac.ability.server.effect.core :as effect]
						[cn.li.ac.ability.server.effect.geom :as geom]
						[cn.li.ac.ability.server.effect.beam]
						[cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
						[cn.li.ac.ability.server.service.skill-effects :as skill-effects]
						[cn.li.mcmod.platform.entity-damage :as entity-damage]
						[cn.li.mcmod.util.log :as log]))

(defonce ^:private pending-tasks
	(atom {}))

(def ^:private mdball-default-life-ticks 50)
(def ^:private mdball-settle-offset-ticks 2)

(defn mdball-near-expire-delay
	"Return delay ticks that settles on MdBall lifecycle near-expire frame.

	Default behavior matches life-2 callback semantics from upstream entities."
	([]
	 (mdball-near-expire-delay mdball-default-life-ticks))
	([life-ticks]
	 (max 1 (- (int (or life-ticks mdball-default-life-ticks))
							mdball-settle-offset-ticks))))

(defn schedule-task!
	[player-uuid delay-ticks task]
	(let [ticks (max 1 (int (or delay-ticks 1)))]
		(swap! pending-tasks update (str player-uuid) (fnil conj [])
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

(defn- run-electron-bomb-beam!
	[{:keys [player-id ctx-id world-id eye look-dir damage]}]
	(try
		(let [result (effect/run-op!
									 {:player-id player-id
										:ctx-id    ctx-id
										:world-id  world-id
										:eye-pos   eye
										:look-dir  look-dir}
									 [:beam {:radius          0.3
													 :query-radius    20.0
													 :step            0.8
													 :max-distance    30.0
													 :visual-distance 28.0
													 :damage          (double (or damage 0.0))
													 :damage-type     :magic
													 :break-blocks?   false
													 :block-energy    0.0
													 :fx-topic        nil}])
					visual-distance (double (or (get-in result [:beam-result :visual-distance]) 28.0))
					end-pos (geom/v+ eye (geom/v* (geom/vnorm look-dir) visual-distance))]
			(ctx-mgr/push-channel-to-player! player-id ctx-id :electron-bomb/fx-beam
																			 {:mode :perform
																				:start eye
																				:end end-pos
																				:hit-distance visual-distance})
			(when (get-in result [:beam-result :performed?])
				(skill-effects/add-skill-exp! player-id :electron-bomb 0.003)))
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
			;; Radiation mark + exp callback (damage-helper linkage)
			(when (fn? on-hit!)
				(try (on-hit! target-uuid) (catch Exception _))))
		(when target-pos
			(ctx-mgr/push-channel-to-player! player-id ctx-id :electron-missile/fx-fire target-pos))
		(catch Exception e
			(log/warn "Delayed ElectronMissile settle failed:" (ex-message e)))))

(defn- run-scatter-bomb-beam!
	[{:keys [player-id ctx-id world-id eye look-dir damage]}]
	(try
		(let [result (effect/run-op!
										 {:player-id player-id
											:ctx-id    ctx-id
											:world-id  world-id
											:eye-pos   eye
											:look-dir  look-dir}
										 [:beam {:radius          0.3
													 :query-radius    20.0
													 :step            0.8
													 :max-distance    25.0
													 :visual-distance 23.0
													 :damage          (double (or damage 0.0))
													 :damage-type     :magic
													 :break-blocks?   false
													 :block-energy    0.0
													 :fx-topic        nil}])
				visual-distance (double (or (get-in result [:beam-result :visual-distance]) 23.0))
				end-pos (geom/v+ eye (geom/v* (geom/vnorm look-dir) visual-distance))]
			(ctx-mgr/push-channel-to-player! player-id ctx-id :scatter-bomb/fx-beam
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
				tasks (get @pending-tasks k)]
		(when (seq tasks)
			(let [next-tasks (volatile! [])]
				(doseq [{:keys [ticks-left] :as task} tasks]
					(if (<= (int ticks-left) 1)
						(run-task! task)
						(vswap! next-tasks conj (update task :ticks-left dec))))
				(let [remaining @next-tasks]
					(if (seq remaining)
						(swap! pending-tasks assoc k remaining)
						(swap! pending-tasks dissoc k)))))))