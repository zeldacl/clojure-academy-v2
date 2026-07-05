(ns cn.li.ac.content.ability.vecmanip.blood-retrograde-fx
	"Client FX for Blood Retrograde: splashes, sprays, walk speed."
	(:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
						[cn.li.ac.ability.client.fx-spec :as fx-spec]
						[cn.li.ac.ability.client.level-effects :as level-effects]
						[cn.li.ac.ability.client.render-util :as ru]))

(def ^:private blood-retrograde-effect-id :blood-retrograde)
(def ^:private sound-id "my_mod:vecmanip.blood_retro")
(def ^:private splash-life 10)
(def ^:private spray-life 1200)

(defn default-blood-retrograde-fx-runtime-state
	[]
	{:effect-state {}
	 :splashes {}
	 :sprays {}})

(defn blood-retrograde-fx-snapshot
	[]
	(or (level-effects/effect-state-snapshot blood-retrograde-effect-id)
			(default-blood-retrograde-fx-runtime-state)))

(defn reset-blood-retrograde-fx-for-test!
	[]
	(level-effects/reset-level-effect-state-for-test!
		blood-retrograde-effect-id
		(default-blood-retrograde-fx-runtime-state))
	nil)

(defn clear-blood-retrograde-owner!
	[owner-key]
	(level-effects/update-effect-state!
		blood-retrograde-effect-id
		(fn [state]
			(-> (or state (default-blood-retrograde-fx-runtime-state))
					(update :effect-state dissoc owner-key)
					(update :splashes dissoc owner-key)
					(update :sprays dissoc owner-key))))
	nil)

(defn- enqueue-state!
	[store ctx-id channel owner-key payload]
	(let [store* (or store (default-blood-retrograde-fx-runtime-state))
				owner-key* (or owner-key [:ctx ctx-id])
				{:keys [mode ticks charge-ratio performed? sound-pos splashes sprays source-player-id world-id]}
				(or payload {})
				base-meta {:owner-key owner-key*
									 :ctx-id ctx-id
									 :channel channel
									 :source-player-id source-player-id
									 :world-id world-id}]
		(case mode
			:start
			(assoc-in store* [:effect-state owner-key*]
								(merge base-meta {:active? true :ticks 0 :charge-ratio 0.0 :performed? false}))
			:update
			(assoc-in store* [:effect-state owner-key*]
								(assoc (merge base-meta (or (get-in store* [:effect-state owner-key*]) {}))
											 :owner-key owner-key*
											 :ctx-id ctx-id
											 :channel channel
											 :source-player-id source-player-id
											 :world-id world-id
											 :active? true
											 :ticks (long (or ticks 0))
											 :charge-ratio (double (or charge-ratio 0.0))
											 :performed? false))
			:perform
			(let [existing (or (get-in store* [:effect-state owner-key*]) {})
						splash-events (mapv (fn [splash]
																	(merge base-meta splash {:ttl splash-life :max-ttl splash-life}))
																(or splashes (:splashes payload)))
						spray-events (mapv (fn [spray]
																 (merge base-meta spray {:ttl spray-life :max-ttl spray-life}))
															 (or sprays (:sprays payload)))
						updated-store (-> store*
															(update-in [:splashes owner-key*] (fnil into []) splash-events)
															(update-in [:sprays owner-key*] (fnil into []) spray-events))]
				(when (or (seq splash-events) (seq spray-events))
					(client-sounds/queue-current-sound-effect!
						{:type :sound :sound-id sound-id :volume 1.0 :pitch 1.0
						 :x (double (or (some-> sound-pos :x) 0.0))
						 :y (double (or (some-> sound-pos :y) 0.0))
						 :z (double (or (some-> sound-pos :z) 0.0))}))
				(assoc-in updated-store [:effect-state owner-key*]
									(assoc (merge base-meta existing)
												 :owner-key owner-key*
												 :ctx-id ctx-id
												 :channel channel
												 :source-player-id source-player-id
												 :world-id world-id
												 :active? true
												 :performed? true)))
			:end
			(assoc-in store* [:effect-state owner-key*]
								(assoc (merge base-meta (or (get-in store* [:effect-state owner-key*]) {}))
											 :owner-key owner-key*
											 :ctx-id ctx-id
											 :channel channel
											 :source-player-id source-player-id
											 :world-id world-id
											 :active? false
											 :ticks 0
											 :charge-ratio 0.0
											 :performed? (boolean performed?)))
			store*)))

(defn- tick-state!
	[store]
	(let [store* (or store (default-blood-retrograde-fx-runtime-state))]
		(assoc store*
					 :effect-state
					 (into {}
								 (keep (fn [[owner-key st]]
												 (when (:active? st)
													 [owner-key (update st :ticks (fnil inc 0))])))
								 (:effect-state store*))
					 :splashes
					 (into {}
								 (keep (fn [[owner-key xs]]
												 (let [live (->> xs
																				 (map #(update % :ttl dec))
																				 (filter #(pos? (long (:ttl %))))
																				 vec)]
													 (when (seq live)
														 [owner-key live]))))
								 (:splashes store*))
					 :sprays
					 (into {}
								 (keep (fn [[owner-key xs]]
												 (let [live (->> xs
																				 (map #(update % :ttl dec))
																				 (filter #(pos? (long (:ttl %))))
																				 vec)]
													 (when (seq live)
														 [owner-key live]))))
								 (:sprays store*)))))

(defn- splash-ops [cam-pos {:keys [x y z size ttl max-ttl]}]
	(let [center {:x (double x) :y (double y) :z (double z)}
				half-size (* 0.5 (double (or size 1.0)))
				right (ru/camera-facing-right-axis center cam-pos)
				up (ru/billboard-up-axis center cam-pos right)
				side (ru/v* right half-size)
				lift (ru/v* up half-size)
				p0 (ru/v+ (ru/v- center side) lift)
				p1 (ru/v+ (ru/v+ center side) lift)
				p2 (ru/v- (ru/v+ center side) lift)
				p3 (ru/v- (ru/v- center side) lift)
				age (long (- (long (or max-ttl splash-life)) (long (or ttl splash-life))))
				frame (max 0 (min 9 age))]
		[(ru/quad-op (str "my_mod:textures/effects/blood_splash/" frame ".png")
								 p0 p1 p2 p3
								 {:r 213 :g 29 :b 29 :a 200})]))

(defn- spray-face-basis [face]
	(case face
		:up    [{:x 0.0 :y 1.0 :z 0.0} {:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z 1.0}]
		:down  [{:x 0.0 :y -1.0 :z 0.0} {:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z -1.0}]
		:north [{:x 0.0 :y 0.0 :z -1.0} {:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 1.0 :z 0.0}]
		:south [{:x 0.0 :y 0.0 :z 1.0} {:x -1.0 :y 0.0 :z 0.0} {:x 0.0 :y 1.0 :z 0.0}]
		:west  [{:x -1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z -1.0} {:x 0.0 :y 1.0 :z 0.0}]
		:east  [{:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z 1.0} {:x 0.0 :y 1.0 :z 0.0}]
		[{:x 0.0 :y 1.0 :z 0.0} {:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z 1.0}]))

(defn- spray-ops [{:keys [x y z face size rotation offset-u offset-v texture-id ttl]}]
	(let [[normal tangent bitangent] (spray-face-basis face)
				center (ru/v+
								 {:x (+ (double x) 0.5)
									:y (+ (double y) 0.5)
									:z (+ (double z) 0.5)}
								 (ru/v* normal 0.51))
				tangent' (ru/rotate-around-axis tangent normal (double (or rotation 0.0)))
				bitangent' (ru/rotate-around-axis bitangent normal (double (or rotation 0.0)))
				shifted (ru/v+ center
											 (ru/v+ (ru/v* tangent' (double (or offset-u 0.0)))
															(ru/v* bitangent' (double (or offset-v 0.0)))))
				half-size (* 0.5 (double (or size 1.0)))
				side (ru/v* tangent' half-size)
				lift (ru/v* bitangent' half-size)
				p0 (ru/v+ (ru/v- shifted side) lift)
				p1 (ru/v+ (ru/v+ shifted side) lift)
				p2 (ru/v- (ru/v+ shifted side) lift)
				p3 (ru/v- (ru/v- shifted side) lift)
				life (if (and ttl (< ttl 60)) (/ (double ttl) 60.0) 1.0)
				tex-folder (if (contains? #{:up :down} face) "wall" "grnd")
				tex-index (max 0 (min 2 (long (or texture-id 0))))]
		[(ru/quad-op (str "my_mod:textures/effects/blood_spray/" tex-folder "/" tex-index ".png")
								 p0 p1 p2 p3
								 {:r 255 :g 255 :b 255 :a (int (+ 40 (* 180 life)))})]))

(defn- local-walk-speed [ticks]
	(let [ratio (min 1.0 (/ (double ticks) 20.0))]
		(float (+ 0.1 (* (- 0.007 0.1) ratio)))))

(defn- build-plan
	[camera-pos _hand-center-pos _tick]
	(let [{:keys [effect-state splashes sprays]} (blood-retrograde-fx-snapshot)
				br (some (fn [st]
									 (when (:active? st) st))
								 (vals effect-state))
				current-splashes (mapcat val splashes)
				current-sprays (mapcat val sprays)
				splash-plan (mapcat #(splash-ops camera-pos %) current-splashes)
				spray-plan (mapcat spray-ops current-sprays)
				ws (when (and br (:active? br))
						 (local-walk-speed (:ticks br)))]
		(when (or (seq splash-plan) (seq spray-plan) ws)
			{:ops (vec (concat splash-plan spray-plan))
			 :local-walk-speed ws})))

(defn init!
	[]
	(fx-spec/register!
		{:id blood-retrograde-effect-id
		 :level {:initial-state (default-blood-retrograde-fx-runtime-state)
						 :enqueue-state-fn enqueue-state!
						 :tick-state-fn tick-state!
						 :build-plan-fn build-plan}
		 :channels {:start {:topic :blood-retrograde/fx-start :mode :start}
								:update {:topic :blood-retrograde/fx-update :mode :update
												 :level-payload (fn [_ _ p]
																				{:ticks (long (or (:ticks p) 0))
																				 :charge-ratio (double (or (:charge-ratio p) 0.0))})}
								:end {:topic :blood-retrograde/fx-end :mode :end
											:level-payload (fn [_ _ p]
																			 {:performed? (boolean (:performed? p))})}
								:perform {:topic :blood-retrograde/fx-perform :mode :perform
													:level-payload (fn [_ _ p]
																					 {:sound-pos (:sound-pos p)
																						:splashes (:splashes p)
																						:sprays (:sprays p)})}}})
	nil)