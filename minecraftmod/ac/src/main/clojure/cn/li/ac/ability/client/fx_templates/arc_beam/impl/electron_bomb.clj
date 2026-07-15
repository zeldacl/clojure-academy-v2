(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.electron-bomb
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [clojure.string :as str]))

(defn- enqueue-state!
	[store ctx-id channel owner-key payload]
	(let [store* (or store {:effect-state {} :beams {}})
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
					{:type :sound :sound-id (modid/namespaced-path "md.eb_spawn") :volume 0.6 :pitch 1.2})
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
			(let [store* (if (and start end)
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
					{:type :sound :sound-id (modid/namespaced-path "md.eb_explode") :volume 0.8 :pitch 1.0})
				(update store* :effect-state dissoc owner-key*))

			:end
			(-> store*
					(update :effect-state dissoc owner-key*)
					(update :beams dissoc owner-key*))

			store*)))

(defn- tick-state!
	[store]
	(let [store* (or store {:effect-state {} :beams {}})]
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
				cx (double (or (:x st) 0.0))
				cy (double (or (:y st) 0.0))
				cz (double (or (:z st) 0.0))
				center (vec3/v3 cx cy cz)
				pulse-end (vec3/v3 (+ cx ox) (+ cy 0.2) (+ cz oz))]
		[(ru/line-op center pulse-end {:r 160 :g 255 :b 190 :a 200})]))

(defn- beam-flash-ops [camera-pos beams]
  "Render with exact EntityMdRaySmall colors:
   inner=0.03 rgba(216,248,216,230), outer=0.045 rgba(106,242,106,50), glow=0.3 a=128"
  (mapcat (fn [{:keys [start end ttl max-ttl]}]
            (let [life-ratio (if (pos? (or max-ttl 1)) (/ (or ttl 1.0) (double max-ttl)) 1.0)
                  blend-in  (min 1.0 (/ (max 0 (- 1.0 life-ratio)) 0.28))
                  blend-out (min 1.0 (/ life-ratio 0.57))
                  am (* blend-in blend-out)
                  shrink (if (< life-ratio 0.3) 0.0 1.0)]
              (ru/billboard-beam-ops camera-pos (vec3/map->v3 start) (vec3/map->v3 end)
                {:width 0.3
                 :core-width 0.045
                 :core-ratio 0.667
                 :outer-color {:r 106 :g 242 :b 106 :a (int (* 50 am shrink))}
                 :inner-color {:r 106 :g 242 :b 106 :a (int (* 128 am shrink))}
                 :line-color  {:r 216 :g 248 :b 216 :a (int (* 230 am shrink))}})))
          beams))

(defn- build-plan [camera-pos _hand-center-pos _tick]
	(let [{:keys [effect-state beams]} (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :electron-bomb)
				cam-v (vec3/map->v3 camera-pos)
				active-ops (mapcat (fn [[_owner-key st]]
														 (when (:active? st)
															 (active-state-ops st)))
													 effect-state)
				beam-ops (mapcat (fn [[_owner-key xs]]
													 (beam-flash-ops cam-v xs))
												 beams)
				ops (vec (concat active-ops beam-ops))]
		(when (seq ops)
			{:ops ops})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:electron-bomb :level] [_ _] {:effect-state {} :beams {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:electron-bomb :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:electron-bomb :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :electron-bomb [store owner-key]
  (-> store (update :effect-state dissoc owner-key) (update :beams dissoc owner-key)))
