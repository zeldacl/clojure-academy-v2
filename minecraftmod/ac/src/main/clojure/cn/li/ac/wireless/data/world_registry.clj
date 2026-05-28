(ns cn.li.ac.wireless.data.world-registry
	"World-scoped registry and base data model for wireless runtime state."
	(:require [cn.li.ac.wireless.core.spatial-index :as si]
						[cn.li.mcmod.util.log :as log]))

(defrecord WiWorldData
	[world-key
	 world
	 runtime])

(defn initial-world-state
	[]
	{:net-lookup {}
	 :node-lookup {}
	 :spatial-index (si/create-spatial-index-value)
	 :networks []
	 :connections []})

(defn create-world-registry-runtime
	[]
	{::runtime ::world-registry-runtime
	 :registry-state* (atom {:world-data-registry {}
											 :world-states {}})})

(def ^:dynamic *world-registry-runtime* nil)

(defonce ^:private installed-world-registry-runtime
	(create-world-registry-runtime))

(def ^:dynamic *world-transaction* nil)

(defn- world-registry-runtime?
	[runtime]
	(and (map? runtime)
			 (= ::world-registry-runtime (::runtime runtime))
			 (some? (:registry-state* runtime))))

(defn call-with-world-registry-runtime
	[runtime f]
	(when-not (world-registry-runtime? runtime)
		(throw (ex-info "Expected world registry runtime"
							{:runtime runtime})))
	(binding [*world-registry-runtime* runtime]
		(f)))

(defmacro with-world-registry-runtime
	[runtime & body]
	`(call-with-world-registry-runtime ~runtime (fn [] ~@body)))

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
	[runtime world wi-data]
	(assoc wi-data :world-key (world-key world) :world world :runtime runtime))

(defn- current-world-registry-runtime
	[]
	(or *world-registry-runtime*
			installed-world-registry-runtime))

(defn- runtime-state-atom
	([]
	 (:registry-state* (current-world-registry-runtime)))
	([runtime]
	 (:registry-state* runtime)))

(defn- runtime-state-snapshot
	([]
	 @(runtime-state-atom))
	([runtime]
	 @(runtime-state-atom runtime)))

(defn- world-data-runtime
	[world-data]
	(or (:runtime world-data)
			(current-world-registry-runtime)))

(defn- transaction-for?
	[world-data]
	(and (map? *world-transaction*)
			 (= (:world-key *world-transaction*) (:world-key world-data))
			 (identical? (:runtime *world-transaction*) (world-data-runtime world-data))))

(defn- current-world-state
	[world-data]
	(if (transaction-for? world-data)
		@(:state* *world-transaction*)
		(get-in (runtime-state-snapshot (world-data-runtime world-data))
					 [:world-states (:world-key world-data)]
					 (initial-world-state))))

(defn- update-world-state!
	[world-data f & args]
	(if (transaction-for? world-data)
		(do
			(vswap! (:state* *world-transaction*) #(apply f % args))
			@(:state* *world-transaction*))
		(let [runtime (world-data-runtime world-data)
					key (:world-key world-data)
					next-state* (volatile! nil)]
			(swap! (runtime-state-atom runtime)
					   (fn [registry-state]
						   (let [current (get-in registry-state [:world-states key] (initial-world-state))
									next (apply f current args)]
							   (vreset! next-state* next)
							   (assoc-in registry-state [:world-states key] next))))
			@next-state*)))

(defn- create-world-data*
	[runtime world]
	(let [key (world-key world)
				world-data (->WiWorldData key world runtime)]
		(swap! (runtime-state-atom runtime)
				   (fn [registry-state]
					   (if (contains? (:world-states registry-state) key)
						   registry-state
						   (assoc-in registry-state [:world-states key] (initial-world-state)))))
		world-data))

(defn create-world-data
	"Create new world data for a world."
	[world]
	(create-world-data* (current-world-registry-runtime) world))

(defn state-value
	[world-data key]
	(get (current-world-state world-data) key))

(defn set-state-value!
	[world-data key value]
	(update-world-state! world-data assoc key value)
	value)

(defn update-state-value!
	[world-data key f & args]
	(apply update-world-state! world-data update key f args)
	(state-value world-data key))

(defn update-state!
	[world-data f & args]
	(apply update-world-state! world-data f args))

(defn net-lookup [world-data] (state-value world-data :net-lookup))
(defn node-lookup [world-data] (state-value world-data :node-lookup))
(defn spatial-index [world-data] (state-value world-data :spatial-index))
(defn networks [world-data] (state-value world-data :networks))
(defn connections [world-data] (state-value world-data :connections))

(defn transact!
	"Run a world-state mutation with serialized access to all world indexes."
	[world-data mutation-fn]
	(if (transaction-for? world-data)
		(mutation-fn world-data)
		(let [runtime (world-data-runtime world-data)
					key (:world-key world-data)
					result* (volatile! nil)]
			(swap! (runtime-state-atom runtime)
					   (fn [registry-state]
						   (let [current (get-in registry-state [:world-states key] (initial-world-state))
									tx-state* (volatile! current)]
							(binding [*world-transaction* {:runtime runtime
																				 :world-key key
																				 :state* tx-state*}]
								(vreset! result* (mutation-fn world-data))
								(assoc-in registry-state [:world-states key] @tx-state*)))))
			@result*)))

(defn get-world-data
	"Get world data for a world, creating it if missing."
	[world]
	(let [runtime (current-world-registry-runtime)
				key (world-key world)
				world-data* (volatile! nil)]
		(swap! (runtime-state-atom runtime)
				   (fn [registry-state]
					   (if-let [existing (get-in registry-state [:world-data-registry key])]
						   (let [updated (if (and (= key (:world-key existing))
															   (identical? world (:world existing))
															   (identical? runtime (world-data-runtime existing)))
													  existing
													  (attach-world-ref runtime world existing))]
							   (vreset! world-data* updated)
							   (cond-> registry-state
								   (not (contains? (:world-states registry-state) key))
								   (assoc-in [:world-states key] (initial-world-state))
								   (not (identical? updated existing))
								   (assoc-in [:world-data-registry key] updated)))
						   (let [created (->WiWorldData key world runtime)]
							   (vreset! world-data* created)
							   (-> registry-state
								   (assoc-in [:world-data-registry key] created)
								   (assoc-in [:world-states key] (initial-world-state)))))))
		@world-data*))

(defn get-world-data-non-create
	"Get world data without creating."
	[world]
	(get-in (runtime-state-snapshot) [:world-data-registry (world-key world)]))

(defn register-world-data!
	"Register a world -> WiWorldData mapping in the registry."
	[world wi-data]
	(let [runtime (current-world-registry-runtime)
				key (world-key world)
				wi-data* (attach-world-ref runtime world wi-data)
				state (current-world-state wi-data)]
		(swap! (runtime-state-atom runtime)
				   (fn [registry-state]
					   (-> registry-state
						   (assoc-in [:world-data-registry key] wi-data*)
						   (assoc-in [:world-states key] state))))
		wi-data*))

(defn remove-world-data!
	"Remove world data (called on world unload)."
	[world]
	(let [key (world-key world)]
		(swap! (runtime-state-atom)
				   (fn [registry-state]
					   (-> registry-state
						   (update :world-data-registry dissoc key)
						   (update :world-states dissoc key)))))
	(log/info (format "Removed WiWorldData for world: %s" (world-key world))))

(defn clear-session-world-data!
	"Remove all wireless world data owned by one server session."
	[owner-or-session-id]
	(let [session-id (if (map? owner-or-session-id)
									(server-session-id owner-or-session-id)
									owner-or-session-id)]
		(swap! (runtime-state-atom)
				   (fn [registry-state]
					   (let [keys-to-remove (->> (concat (keys (:world-data-registry registry-state))
																   (keys (:world-states registry-state)))
													 (filter (fn [[entry-session-id _world-id]]
															 (= session-id entry-session-id)))
													 distinct
													 vec)]
						   (-> registry-state
							   (update :world-data-registry #(apply dissoc % keys-to-remove))
							   (update :world-states #(apply dissoc % keys-to-remove))))))
		nil))

(defn registry-snapshot
	"Return current in-memory registry snapshot. Intended for tests/diagnostics."
	[]
	(:world-data-registry (runtime-state-snapshot)))

(defn reset-registry!
	"Reset in-memory world registry. Intended for tests only."
	[]
	(reset! (runtime-state-atom)
				{:world-data-registry {}
				 :world-states {}}))