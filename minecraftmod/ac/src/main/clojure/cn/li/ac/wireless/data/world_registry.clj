(ns cn.li.ac.wireless.data.world-registry
	"World-scoped registry and base data model for wireless runtime state."
	(:require [cn.li.ac.wireless.core.spatial-index :as si]
						[cn.li.mcmod.util.log :as log]))

(defrecord WiWorldData
	[world-key
	 world
	 state])

(def ^:private world-data-registry (atom {}))

(defn- require-world-owner-value
	[world label value]
	(if (some? value)
		value
		(throw (ex-info (format "Wireless world owner requires %s" label)
								{:world world
								 :required label}))))

(defn- invoke-no-arg
	[target method-name]
	(try
		(clojure.lang.Reflector/invokeInstanceMethod target method-name (object-array 0))
		(catch Throwable _ nil)))

(defn- resource-key-value
	[value]
	(cond
		(nil? value) nil
		(or (keyword? value) (string? value) (symbol? value) (number? value)) value
		:else (or (some-> value (invoke-no-arg "location") str)
						(some-> value (invoke-no-arg "getValue") str)
						(str value))))

(defn- server-session-id
	[world]
	(require-world-owner-value
		world
		":server-session-id"
		(if (map? world)
			(or (:server-session-id world) (:session-id world))
			(when-let [server (invoke-no-arg world "getServer")]
				[:server (System/identityHashCode server)]))))

(defn- world-id
	[world]
	(require-world-owner-value
		world
		":world-id"
		(cond
			(map? world) (or (:world-id world) (:dimension-id world))
			(or (keyword? world) (string? world) (symbol? world) (number? world)) nil
			:else (or (some-> world (invoke-no-arg "dimension") resource-key-value)
							(some-> world (invoke-no-arg "getRegistryKey") resource-key-value)))))

(defn world-key
	"Return the stable registry key for a world.
	The key intentionally avoids using the mutable world object identity directly."
	[world]
	[(server-session-id world) (world-id world)])

(defn- attach-world-ref
	[world wi-data]
	(assoc wi-data :world-key (world-key world) :world world))

(defn create-world-data
	"Create new world data for a world."
	[world]
	(->WiWorldData
		(world-key world)
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
	(let [key (world-key world)]
	(or (when-let [existing (get @world-data-registry key)]
			(if (and (= key (:world-key existing))
							 (identical? world (:world existing)))
				existing
				(let [updated (attach-world-ref world existing)]
					(swap! world-data-registry assoc key updated)
					updated)))
			(let [created (create-world-data world)]
				(swap! world-data-registry #(if (contains? % key) % (assoc % key created)))
				(get @world-data-registry key)))))

(defn get-world-data-non-create
	"Get world data without creating."
	[world]
	(get @world-data-registry (world-key world)))

(defn register-world-data!
	"Register a world -> WiWorldData mapping in the registry."
	[world wi-data]
	(let [wi-data* (attach-world-ref world wi-data)]
		(swap! world-data-registry assoc (world-key world) wi-data*)
		wi-data*))

(defn remove-world-data!
	"Remove world data (called on world unload)."
	[world]
	(swap! world-data-registry dissoc (world-key world))
	(log/info (format "Removed WiWorldData for world: %s" (world-key world))))

(defn clear-session-world-data!
	"Remove all wireless world data owned by one server session."
	[owner-or-session-id]
	(let [session-id (if (map? owner-or-session-id)
									(server-session-id owner-or-session-id)
									owner-or-session-id)]
		(swap! world-data-registry
				 (fn [registry]
					 (into {}
							 (remove (fn [[[entry-session-id _world-id] _world-data]]
									 (= session-id entry-session-id)))
							 registry))))
	nil)

(defn registry-snapshot
	"Return current in-memory registry snapshot. Intended for tests/diagnostics."
	[]
	@world-data-registry)

(defn reset-registry!
	"Reset in-memory world registry. Intended for tests only."
	[]
	(reset! world-data-registry {}))