(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.mag-manip
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.util.math.vec3 :as vec3]
            [clojure.string :as str]))

(def ^:private hold-loop-sound "my_mod:em.lf_loop")
(def ^:private perform-sound "my_mod:em.mag_manip")


(def ^:private default-state
	{:active? false
	 :focus nil
	 :block-id nil
	 :ticks 0})











(defn- enqueue-state! [store ctx-id channel owner-key payload]
	(let [store* (if (contains? (or store {}) :states)
								 (or store {:states {}})
								 {:states {}})
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
								 (or store {:states {}})
								 {:states {}})]
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
	(let [{:keys [states]} (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :mag-manip)
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
		(hand-effects/enqueue-hand-effect! :mag-manip ctx-id channel
			(assoc (or payload {}) :mode mode))))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:mag-manip :hand] [_ _] {:states {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:mag-manip :hand]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:mag-manip :hand] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-transform-fn :mag-manip [] (current-hand-transform))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :mag-manip [store owner-key]
  (assoc store :states (dissoc (:states store) owner-key)))
