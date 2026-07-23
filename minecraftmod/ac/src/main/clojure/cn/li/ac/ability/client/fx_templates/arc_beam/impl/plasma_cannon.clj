(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.plasma-cannon
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
            [clojure.string :as str]))

(def ^:private loop-sound (modid/namespaced-path "vecmanip.plasma_cannon"))
(def ^:private charged-sound (modid/namespaced-path "vecmanip.plasma_cannon_t"))









(defn- enqueue-state!
	[store ctx-id channel owner-key payload]
	(let [store* (or store {:effect-state {}})
				owner-key* (or owner-key [:ctx ctx-id])
				{:keys [mode charge-ticks fully-charged? charge-pos flight-ticks
								state destination pos performed? source-player-id world-id]} (or payload {})
				base-meta {:owner-key owner-key*
									 :queue-owner (client-particles/current-effect-owner)
									 :ctx-id ctx-id
									 :channel channel
									 :source-player-id source-player-id
									 :world-id world-id}]
		(case mode
			:start
			(do
				(client-sounds/queue-sound-effect! (:queue-owner base-meta)
					{:type :sound :sound-id loop-sound :volume 0.5 :pitch 1.0})
				(assoc-in store* [:effect-state owner-key*]
									(merge base-meta
												 {:active? true :charge-ticks 0 :charge-pos (:charge-pos payload)
												:flight-ticks 0 :state :charging :destination nil
												:performed? false})))
			:update
			(let [updated (assoc-in store* [:effect-state owner-key*]
												(assoc (merge base-meta (or (get-in store* [:effect-state owner-key*]) {}))
													 :owner-key owner-key*
													 :ctx-id ctx-id
													 :channel channel
													 :source-player-id source-player-id
													 :world-id world-id
													 :active? true
													 :charge-ticks (long (or charge-ticks 0))
													 :flight-ticks (long (or flight-ticks 0))
													 :state (or state :charging)
													 :charge-pos (or charge-pos (get-in store* [:effect-state owner-key* :charge-pos]))
													 :destination (or destination (get-in store* [:effect-state owner-key* :destination]))))]
					(when (boolean fully-charged?)
						(client-sounds/queue-sound-effect! (:queue-owner base-meta)
							{:type :sound :sound-id charged-sound :volume 0.5 :pitch 1.0}))
					updated)
			:perform
			(do
				(when (map? pos)
					(let [tx (double (:x pos)) ty (double (:y pos)) tz (double (:z pos))]
						(client-particles/queue-particle-effect! (:queue-owner base-meta)
							{:type :particle :particle-type :explosion-large
							 :x tx :y ty :z tz :count 1 :speed 0.0
							 :offset-x 0.0 :offset-y 0.0 :offset-z 0.0})
						(dotimes [_ 12]
							(client-particles/queue-particle-effect! (:queue-owner base-meta)
								{:type :particle :particle-type :smoke-large
								 :x (+ tx (- (* (rand) 10.0) 5.0))
								 :y (+ ty (- (* (rand) 5.0) 2.5))
								 :z (+ tz (- (* (rand) 10.0) 5.0))
								 :count 1 :speed (+ 0.1 (* (rand) 0.3))
								 :offset-x 0.5 :offset-y 0.5 :offset-z 0.5}))
						(client-sounds/queue-sound-effect! (:queue-owner base-meta)
							{:type :sound :sound-id "minecraft:entity.generic.explode"
							 :volume 3.0 :pitch 0.8 :x tx :y ty :z tz})))
				store*)
			:end
			(assoc-in store* [:effect-state owner-key*]
									(merge base-meta {:active? false :performed? (boolean performed?)}))
			store*)))

(defn- tick-state!
	[store]
	(let [store* (or store {:effect-state {}})]
		(update store* :effect-state
			(fn [states]
				(reduce-kv
					(fn [acc owner-key st]
						(if-not (:active? st)
							acc
							(let [ticks (inc (long (or (:ticks st) 0)))]
								(when (and (pos? ticks) (zero? (mod ticks 10)))
									(client-sounds/queue-sound-effect! (:queue-owner st)
										{:type :sound :sound-id loop-sound :volume 0.4 :pitch 1.0}))
								(let [cp (:charge-pos st)]
									(when (and cp (= :go (:state st)))
										(client-particles/queue-particle-effect! (:queue-owner st)
											{:type :particle :particle-type :flame
											 :x (double (:x cp)) :y (double (:y cp)) :z (double (:z cp))
											 :count 4 :speed 0.2
											 :offset-x 0.5 :offset-y 0.5 :offset-z 0.5})))
								(assoc acc owner-key (assoc st :ticks ticks)))))
					{}
					states)))))

(defn- plasma-balls
	[{:keys [x y z]} ticks state]
	(let [t (double (or ticks 0))
				phase (* 0.23 t)
				state-mul (if (= state :go) 1.25 1.0)
				base-r (* 0.55 state-mul)
				h-wave (* 0.08 (Math/sin (* 0.15 t)))]
		[{:x x :y (+ y h-wave) :z z :size (* 0.95 state-mul)}
		 {:x (+ x (* base-r (Math/cos (+ phase 0.0))))
			:y (+ y (* 0.18 (Math/sin (+ phase 0.7))))
			:z (+ z (* base-r (Math/sin (+ phase 0.0))))
			:size 0.62}
		 {:x (+ x (* base-r (Math/cos (+ phase 2.09))))
			:y (+ y (* 0.18 (Math/sin (+ phase 2.79))))
			:z (+ z (* base-r (Math/sin (+ phase 2.09))))
			:size 0.62}
		 {:x (+ x (* base-r (Math/cos (+ phase 4.18))))
			:y (+ y (* 0.18 (Math/sin (+ phase 4.91))))
			:z (+ z (* base-r (Math/sin (+ phase 4.18))))
			:size 0.62}
		 {:x (+ x (* 0.35 (Math/cos (* 0.41 t))))
			:y (+ y (* 0.25 (Math/sin (* 0.47 t))))
			:z (+ z (* 0.35 (Math/sin (* 0.41 t))))
			:size 0.45}
		 {:x (+ x (* 0.3 (Math/cos (+ (* 0.53 t) 1.3))))
			:y (+ y (* 0.22 (Math/sin (+ (* 0.59 t) 0.4))))
			:z (+ z (* 0.3 (Math/sin (+ (* 0.53 t) 1.3))))
			:size 0.4}]))

(defn- plasma-state-ops
	[st]
	(when (and (:active? st) (map? (:charge-pos st)))
		(let [cp (:charge-pos st)
					ticks (long (or (:ticks st) 0))
					state (:state st)
					charge-ticks (long (or (:charge-ticks st) 0))
					ramp (min 1.0 (/ charge-ticks 24.0))
					alpha (double (* (if (= state :go) 1.0 0.85) (+ 0.2 (* 0.8 ramp))))
					radius (+ 0.95 (* 0.35 ramp) (* 0.08 (Math/sin (* 0.21 ticks))))
					balls (plasma-balls cp ticks state)]
			[{:kind :plasma-body
				:center {:x (double (:x cp)) :y (double (:y cp)) :z (double (:z cp))}
				:radius (double radius)
				:alpha (double alpha)
				:balls balls}])))

(defn- build-plan
	[_camera-pos _hand-center-pos _tick]
	(let [ops (mapcat plasma-state-ops (vals (:effect-state (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :plasma-cannon))))]
		(when (seq ops)
			{:ops (vec ops)})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:plasma-cannon :level] [_ _] {:effect-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:plasma-cannon :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:plasma-cannon :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :plasma-cannon
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :plasma-cannon [_ store owner-key]
  (update store :effect-state dissoc owner-key))
