(ns cn.li.ac.content.ability.electromaster.mag-manip-fx
	"Client FX for Mag Manip hold/throw lifecycle."
	(:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
						[cn.li.ac.ability.client.fx-spec :as fx-spec]
						[cn.li.ac.ability.client.hand-effects :as hand-effects]))

(def ^:private hold-loop-sound "my_mod:em.lf_loop")
(def ^:private perform-sound "my_mod:em.mag_manip")
(def ^:private mag-manip-effect-id :mag-manip)

(def ^:private default-state
	{:active? false
	 :focus nil
	 :block-id nil
	 :ticks 0})

(defn default-mag-manip-fx-runtime-state
	[]
	{:states {}})

(defn mag-manip-fx-snapshot []
	(or (hand-effects/effect-state-snapshot mag-manip-effect-id)
			(default-mag-manip-fx-runtime-state)))

(defn reset-mag-manip-fx-for-test! []
	(hand-effects/reset-hand-effect-state-for-test!
		mag-manip-effect-id
		(default-mag-manip-fx-runtime-state))
	nil)

(defn clear-mag-manip-owner!
	[owner-key]
	(hand-effects/update-effect-state!
		mag-manip-effect-id
		(fn [store]
			(let [store* (if (contains? (or store {}) :states)
										 (or store (default-mag-manip-fx-runtime-state))
										 (default-mag-manip-fx-runtime-state))]
				(assoc store* :states (dissoc (:states store*) owner-key)))))
	nil)

(defn current-state
	[selector]
	(let [{:keys [states]} (mag-manip-fx-snapshot)]
		(or (get states selector)
				default-state)))

(defn- enqueue-state! [store ctx-id channel owner-key payload]
	(let [store* (if (contains? (or store {}) :states)
								 (or store (default-mag-manip-fx-runtime-state))
								 (default-mag-manip-fx-runtime-state))
				{:keys [mode focus block-id source-player-id world-id]} payload
				owner-key* (or owner-key [:ctx ctx-id])
				base-meta {:owner-key owner-key*
									 :queue-owner (client-sounds/current-effect-owner)
									 :ctx-id ctx-id
									 :channel channel
									 :source-player-id source-player-id
									 :world-id world-id}]
		(case mode
			:hold-start
			(do
				(client-sounds/queue-current-sound-effect!
					{:type :sound :sound-id hold-loop-sound :volume 0.5 :pitch 1.0})
				(assoc-in store* [:states owner-key*]
									(merge default-state base-meta
												 {:active? true
													:focus focus
													:block-id block-id
													:ticks 0})))

			:hold-loop
			(update-in store* [:states owner-key*]
				(fn [state]
					(-> (merge default-state state base-meta)
							(assoc :active? true)
							(cond-> focus (assoc :focus focus))
							(cond-> block-id (assoc :block-id block-id)))))

			:throw
			(do
				(client-sounds/queue-current-sound-effect!
					{:type :sound :sound-id perform-sound :volume 0.9 :pitch 1.0})
				(update-in store* [:states owner-key*]
					(fn [state]
						(merge default-state state base-meta {:active? false}))))

			:end
			(update store* :states dissoc owner-key*)

			store*)))

(defn- tick-state! [store]
	(let [store* (if (contains? (or store {}) :states)
								 (or store (default-mag-manip-fx-runtime-state))
								 (default-mag-manip-fx-runtime-state))]
		(update store* :states
			(fn [states]
				(into {}
							(map (fn [[owner-key state]]
										 (if-not (:active? state)
											 [owner-key state]
											 (let [ticks (inc (long (or (:ticks state) 0)))]
												 (when (zero? (mod ticks 12))
													 (client-sounds/queue-sound-effect! (:queue-owner state)
														 {:type :sound :sound-id hold-loop-sound :volume 0.35 :pitch 1.0}))
												 [owner-key (assoc state :ticks ticks)]))))
							states)))))

(defn- current-hand-transform []
	(let [{:keys [states]} (mag-manip-fx-snapshot)
				state (some (fn [[_ state]]
											(when (:active? state) state))
										states)]
		(when (:active? state)
			(let [ticks (double (or (:ticks state) 0))
						phase (* 0.22 ticks)
						y (+ 0.02 (* 0.01 (Math/sin phase)))]
				{:translate [0.0 y 0.0]}))))

(defn- on-fx-hold [ctx-id channel payload]
	(when-let [mode (:mode payload)]
		(hand-effects/enqueue-hand-effect! mag-manip-effect-id ctx-id channel
			(assoc (or payload {}) :mode mode))))

(defn init! []
	(fx-spec/register!
		{:id mag-manip-effect-id
		 :hand {:initial-state (default-mag-manip-fx-runtime-state)
						:enqueue-state-fn enqueue-state!
						:tick-state-fn tick-state!
						:transform-fn current-hand-transform}
		 :channels {:hold {:topic :mag-manip/fx-hold :targets [:hand] :handler on-fx-hold}
								:throw {:topic :mag-manip/fx-throw :mode :throw :targets [:hand]}
								:end {:topic :mag-manip/fx-end :mode :end :targets [:hand]}}})
	nil)