(ns cn.li.ac.wireless.data.world-registry
	"World-scoped registry and base data model for wireless runtime state."
	(:require [cn.li.ac.wireless.core.spatial-index :as si]
						[cn.li.mcmod.util.log :as log]))

(defrecord WiWorldData
	[world
	 state])

(def ^:private world-data-registry (atom {}))

(defn create-world-data
	"Create new world data for a world."
	[world]
	(->WiWorldData
		world
		(atom {:net-lookup {}
					 :node-lookup {}
					 :spatial-index (si/create-spatial-index-value)
					 :networks []
					 :connections []})))

(defn state-value
	[world-data key]
	(get @(:state world-data) key))

(defn set-state-value!
	[world-data key value]
	(swap! (:state world-data) assoc key value)
	value)

(defn update-state-value!
	[world-data key f & args]
	(apply swap! (:state world-data) update key f args)
	(state-value world-data key))

(defn update-state!
	[world-data f & args]
	(apply swap! (:state world-data) f args))

(defn net-lookup [world-data] (state-value world-data :net-lookup))
(defn node-lookup [world-data] (state-value world-data :node-lookup))
(defn spatial-index [world-data] (state-value world-data :spatial-index))
(defn networks [world-data] (state-value world-data :networks))
(defn connections [world-data] (state-value world-data :connections))

(defn transact!
	"Run a world-state mutation with serialized access to all world indexes."
	[world-data mutation-fn]
	(locking world-data
		(mutation-fn world-data)))

(defn get-world-data
	"Get world data for a world, creating it if missing."
	[world]
	(or (get @world-data-registry world)
			(let [created (create-world-data world)]
				(swap! world-data-registry #(if (contains? % world) % (assoc % world created)))
				(get @world-data-registry world))))

(defn get-world-data-non-create
	"Get world data without creating."
	[world]
	(get @world-data-registry world))

(defn register-world-data!
	"Register a world -> WiWorldData mapping in the registry."
	[world wi-data]
	(swap! world-data-registry assoc world wi-data)
	wi-data)

(defn remove-world-data!
	"Remove world data (called on world unload)."
	[world]
	(swap! world-data-registry dissoc world)
	(log/info (format "Removed WiWorldData for world: %s" world)))

(defn reset-registry!
	"Reset in-memory world registry. Intended for tests only."
	[]
	(reset! world-data-registry {}))