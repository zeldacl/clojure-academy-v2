(ns cn.li.ac.content.ability.meltdowner.electron-bomb-fx
	"Client FX for ElectronBomb: orbiting ball spawn + beam flash."
	(:require [cn.li.ac.ability.client.effects.particles :as client-particles]
						[cn.li.ac.ability.client.effects.sounds :as client-sounds]
						[cn.li.ac.ability.client.fx-registry :as fx-registry]
						[cn.li.ac.ability.client.level-effects :as level-effects]
						[cn.li.ac.ability.client.render-util :as ru]))

(def ^:private electron-bomb-effect-id :electron-bomb)

(defn default-electron-bomb-fx-runtime-state
	[]
	{:effect-state {}
	 :beams {}})

(defn electron-bomb-fx-snapshot
	[]
	(or (level-effects/effect-state-snapshot electron-bomb-effect-id)
			(default-electron-bomb-fx-runtime-state)))

(defn reset-electron-bomb-fx-for-test!
	[]
	(level-effects/reset-level-effect-state-for-test!
		electron-bomb-effect-id
		(default-electron-bomb-fx-runtime-state))
	nil)

(defn clear-electron-bomb-owner!
	[owner-key]
	(level-effects/update-effect-state!
		electron-bomb-effect-id
		(fn [store]
			(-> (or store (default-electron-bomb-fx-runtime-state))
					(update :effect-state dissoc owner-key)
					(update :beams dissoc owner-key))))
	nil)

(defn- enqueue-state!
	[store {:keys [payload ctx-id channel owner-key]}]
	(let [store* (or store (default-electron-bomb-fx-runtime-state))
				owner-key* (or owner-key [:ctx ctx-id])
				{:keys [mode x y z dx dy dz start end source-player-id world-id]} (or payload {})
				base-meta {:owner-key owner-key*
									 :queue-owner (client-particles/current-effect-owner)
									 :ctx-id ctx-id
									 :channel channel
									 :source-player-id source-player-id
									 :world-id world-id}]
		(case mode
			:spawn
			(do
				(client-sounds/queue-sound-effect! (:queue-owner base-meta)
					{:type :sound :sound-id "my_mod:md.eb_spawn" :volume 0.6 :pitch 1.2})
				(assoc-in store* [:effect-state owner-key*]
									(merge base-meta
												 {:active? true
													:ticks 0
													:x (double (or x 0.0))
													:y (double (or y 0.0))
													:z (double (or z 0.0))
													:dx (double (or dx 0.0))
													:dy (double (or dy 0.0))
													:dz (double (or dz 0.0))})))

			:beam
			(let [store** (if (and start end)
											(update-in store* [:beams owner-key*] (fnil conj [])
																 (merge base-meta
																				{:start start
																				 :end end
																				 :ttl 8
																				 :max-ttl 8
																				 :performed? (boolean (:performed? payload))
																				 :target-uuid (:target-uuid payload)}))
											store*)]
				(when (and start end)
					(client-particles/queue-particle-effect! (:queue-owner base-meta)
						{:type :particle :particle-type :electric-spark
						 :x (double (or (:x end) 0.0))
						 :y (double (or (:y end) 0.0))
						 :z (double (or (:z end) 0.0))
						 :count 8 :speed 0.2
						 :offset-x 0.5 :offset-y 0.5 :offset-z 0.5}))
				(client-sounds/queue-sound-effect! (:queue-owner base-meta)
					{:type :sound :sound-id "my_mod:md.eb_explode" :volume 0.8 :pitch 1.0})
				(update store** :effect-state dissoc owner-key*))

			:end
			(-> store*
					(update :effect-state dissoc owner-key*)
					(update :beams dissoc owner-key*))

			store*)))

(defn- tick-state!
	[store]
	(let [store* (or store (default-electron-bomb-fx-runtime-state))]
		(-> store*
				(update :effect-state
					(fn [states]
						(into {}
									(keep (fn [[owner-key st]]
													(when (:active? st)
														(let [ticks (inc (long (or (:ticks st) 0)))]
															(when (zero? (mod ticks 3))
																(let [angle (* 0.4 (double ticks))
																			ox (* 0.9 (Math/cos angle))
																			oz (* 0.9 (Math/sin angle))]
																	(client-particles/queue-particle-effect! (:queue-owner st)
																		{:type :particle :particle-type :electric-spark
																		 :x (+ (:x st) ox)
																		 :y (:y st)
																		 :z (+ (:z st) oz)
																		 :count 1 :speed 0.05
																		 :offset-x 0.1 :offset-y 0.1 :offset-z 0.1})))
															(when-not (> ticks 40)
																[owner-key (assoc st :ticks ticks)])))))
												states)))
				(update :beams
					(fn [by-owner]
						(into {}
									(keep (fn [[owner-key beams]]
													(let [live (->> beams
																					(map #(update % :ttl dec))
																					(filter #(pos? (long (:ttl %))))
																					vec)]
														(when (seq live)
															[owner-key live]))))
									by-owner))))))

(defn- active-state-ops [st]
	(let [ticks (long (or (:ticks st) 0))
				angle (* 0.4 (double ticks))
				ox (* 0.9 (Math/cos angle))
				oz (* 0.9 (Math/sin angle))
				center {:x (double (or (:x st) 0.0))
								:y (double (or (:y st) 0.0))
								:z (double (or (:z st) 0.0))}
				pulse-end {:x (+ (:x center) ox)
									 :y (+ (:y center) 0.2)
									 :z (+ (:z center) oz)}]
		[(ru/line-op center pulse-end {:r 160 :g 255 :b 190 :a 200})]))

(defn- beam-flash-ops [camera-pos beams]
	(mapcat (fn [{:keys [start end ttl max-ttl]}]
						(let [life-ratio (if (pos? (double (or max-ttl 1)))
															 (/ (double (or ttl 1)) (double max-ttl))
															 1.0)
									alpha (int (+ 60 (* 160 life-ratio)))
									width (+ 0.03 (* 0.05 life-ratio))]
							(ru/billboard-beam-ops camera-pos start end
																		 {:width width
																			:core-width (* width 0.35)
																			:outer-color {:r 140 :g 255 :b 170 :a alpha}
																			:inner-color {:r 240 :g 255 :b 235 :a (min 255 (+ alpha 30))}
																			:line-color {:r 200 :g 255 :b 200 :a alpha}})))
					beams))

(defn- build-plan [camera-pos _hand-center-pos _tick]
	(let [{:keys [effect-state beams]} (electron-bomb-fx-snapshot)
				active-ops (mapcat (fn [[_owner-key st]]
														 (when (:active? st)
															 (active-state-ops st)))
													 effect-state)
				beam-ops (mapcat (fn [[_owner-key xs]]
													 (beam-flash-ops camera-pos xs))
												 beams)
				ops (vec (concat active-ops beam-ops))]
		(when (seq ops)
			{:ops ops})))

(defn init!
	[]
	(level-effects/register-level-effect! electron-bomb-effect-id
		{:initial-state (default-electron-bomb-fx-runtime-state)
		 :enqueue-state-fn enqueue-state!
		 :tick-state-fn tick-state!
		 :build-plan-fn build-plan})
	(fx-registry/register-fx-channels!
		[:electron-bomb/fx-spawn :electron-bomb/fx-beam :electron-bomb/fx-end]
		(fn [ctx-id channel payload]
			(let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
				(case channel
					:electron-bomb/fx-spawn
					(level-effects/enqueue-level-effect! electron-bomb-effect-id
						(merge meta-payload
									 {:mode :spawn
										:x (:x payload) :y (:y payload) :z (:z payload)
										:dx (:dx payload) :dy (:dy payload) :dz (:dz payload)})
						{:ctx-id ctx-id :channel channel})
					:electron-bomb/fx-beam
					(level-effects/enqueue-level-effect! electron-bomb-effect-id
						(merge meta-payload
									 {:mode :beam
										:start (:start payload)
										:end (:end payload)})
						{:ctx-id ctx-id :channel channel})
					:electron-bomb/fx-end
					(level-effects/enqueue-level-effect! electron-bomb-effect-id
						(merge meta-payload {:mode :end})
						{:ctx-id ctx-id :channel channel})
					nil))))
	nil)