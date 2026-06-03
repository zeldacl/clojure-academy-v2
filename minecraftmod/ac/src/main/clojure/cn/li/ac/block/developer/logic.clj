(ns cn.li.ac.block.developer.logic
	(:require [clojure.string :as str]
            [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.gui.open :as gui-open]
						[cn.li.mcmod.block.dsl :as bdsl]
						[cn.li.mcmod.block.state-schema :as state-schema]
						[cn.li.mcmod.platform.world :as world]
						[cn.li.mcmod.platform.be :as platform-be]
						[cn.li.mcmod.platform.entity :as entity]
						[cn.li.mcmod.platform.position :as pos]
						[cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.domain.developer :as developer]
						[cn.li.ac.block.developer.config :as dev-config]
						[cn.li.ac.block.developer.schema :as dev-schema]
						[cn.li.ac.block.energy-converter.wireless-impl :as wireless-impl]
						[cn.li.mcmod.util.log :as log]))

(def ^:private dev-rt
	(machine-runtime/schema-runtime dev-schema/developer-schema :server-only? true))

(def dev-state-schema (:server-schema dev-rt))
(def dev-default-state (:default-state dev-rt))
(def dev-scripted-load-fn (:load-fn dev-rt))
(def dev-scripted-save-fn (:save-fn dev-rt))

(def developer-multiblock-positions
	[[0 0 0]
	 [0 1 0]
	 [0 0 1] [0 1 1] [0 2 1]
	 [0 0 2] [0 1 2] [0 2 2]])

(defn- validate-structure [level pos block-spec]
	(try (bdsl/is-multi-block-complete? level pos block-spec)
			 (catch Exception e
				 (log/debug "Developer structure validation failed:" (ex-message e))
				 false)))

(defn- tier-kw-for-block-id [block-id]
	(or (developer/developer-type-for-block-id block-id) :normal))

(defn- ensure-inventory-shape [state]
	(let [v2 (vec (take 2 (concat (vec (:inventory state [])) (repeat nil))))]
		(assoc state :inventory v2)))

(defn- ensure-tier-defaults [state be]
	(let [tier (tier-kw-for-block-id (platform-be/get-block-id be))
				cfg (dev-config/tier-config tier)]
		(-> state
				(merge {:tier (name tier)
								:max-energy (:max-energy cfg)
								:wireless-bandwidth (:wireless-bandwidth cfg)})
				ensure-inventory-shape)))

(defn- tick-development [state ^long ticker]
	(if-not (:is-developing state)
		state
		(let [tier (keyword (:tier state :normal))
					{:keys [energy-per-stimulation stimulation-interval-ticks]}
					(dev-config/tier-config tier)]
			(if (zero? (mod ticker (long stimulation-interval-ticks)))
				(let [e (double (:energy state 0.0))
							cost (double energy-per-stimulation)]
					(if (>= e cost)
						(-> state (update :energy - cost) (update :development-progress + 1.0))
						(assoc state :is-developing false)))
				state))))

(defn developer-tick-state [state {:keys [level pos be]}]
	(let [ticker (inc (long (get state :update-ticker 0)))
				state1 (-> state (assoc :update-ticker ticker) (ensure-tier-defaults be))
				state2 (if (zero? (mod ticker (dev-config/validate-interval)))
								 (let [block-spec (some-> (platform-be/get-block-id be) bdsl/get-block-spec)]
									 (assoc state1 :structure-valid (boolean (and block-spec (validate-structure level pos block-spec)))))
								 state1)
				state3 (if-not (:structure-valid state2 false) (assoc state2 :is-developing false) state2)
				state4 (if (:structure-valid state3 false) (tick-development state3 ticker) state3)]
		(-> state4
				(assoc :wireless-inject-last-tick (double (:wireless-inject-this-tick state4 0.0)))
				(assoc :wireless-inject-this-tick 0.0))))

(def developer-tick-fn
	(machine-runtime/make-tick-fn
		{:default-state dev-default-state
		 :tick-state developer-tick-state}))

(defn open-developer-gui-for [controller-block-id]
	(fn [{:keys [player world pos sneaking]}]
		(when (and player world pos (not sneaking))
			(try
				(let [block-spec (bdsl/get-block-spec controller-block-id)
							controller-pos (or (when block-spec (bdsl/resolve-multi-block-master-pos world pos block-spec)) pos)]
					(if (world/world-is-client-side* world)
						(gui-open/open-gui-by-type player :developer world controller-pos)
						(when-let [be (world/world-get-tile-entity* world controller-pos)]
							(let [state (machine-runtime/state-or-default be dev-default-state)
										pid (uuid/player-uuid player)
										cur (str (:user-uuid state ""))]
								(when (or (str/blank? cur) (= cur pid))
									(machine-runtime/commit-state! be world controller-pos state
									                               (assoc state :user-uuid pid :user-name (entity/player-get-name player)))
									(gui-open/open-gui-by-type player :developer world controller-pos))))))
				(catch Exception e
					(log/error "Failed to open Developer GUI:" (ex-message e))
					nil)))))

(defn- commit-dev-state! [tile new-state]
	(let [world (platform-be/be-get-world-safe tile)
				pos (when world (try (pos/position-get-block-pos tile) (catch Exception _ nil)))
				old (machine-runtime/state-or-default tile dev-default-state)]
		(machine-runtime/commit-state! tile world pos old new-state)))

(defn create-dev-receiver-cap [be]
	(wireless-impl/create-wireless-receiver
		be
		(fn [] (machine-runtime/state-or-default be dev-default-state))
		(fn [s] (commit-dev-state! be s))
		{:after-inject!
		 (fn [^double accepted]
			 (when (pos? accepted)
				 (let [st (machine-runtime/state-or-default be dev-default-state)
							 cur (double (:wireless-inject-this-tick st 0.0))]
					 (commit-dev-state! be (assoc st :wireless-inject-this-tick (+ cur accepted))))))}))

(defn try-pull-energy! [tile ^double amount]
	(boolean
		(try
			(when tile
				(let [state (machine-runtime/state-or-default tile dev-default-state)
							e (double (get state :energy 0.0))]
					(when (>= e amount)
						(commit-dev-state! tile (assoc state :energy (- e amount)))
						true)))
			(catch Exception _ false))))

(defn get-energy [tile]
	(double (get (or (platform-be/get-custom-state tile) {}) :energy 0.0)))

(defn get-max-energy [tile]
	(double (get (or (platform-be/get-custom-state tile) {}) :max-energy 50000.0)))