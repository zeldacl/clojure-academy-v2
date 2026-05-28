(ns cn.li.mcmod.config.registry
	"Platform-neutral config descriptor and runtime value registry.")

(defn- log-info
	[& xs]
	(when-let [f (requiring-resolve 'cn.li.mcmod.util.log/info)]
		(apply f xs)))

(defn- default-config-registry-runtime-state []
	{:descriptor-registry {}
	 :value-registry {}})

(defn create-config-registry-runtime
	([] (create-config-registry-runtime {}))
	([{:keys [state*]}]
	 {:cn.li.mcmod.config.registry/runtime ::config-registry-runtime
	  :state* (or state* (atom (default-config-registry-runtime-state)))}))

(def ^:dynamic *config-registry-runtime* nil)

(defonce ^:private installed-config-registry-runtime
	(create-config-registry-runtime))

(defn- config-registry-state-atom []
	(:state* (or *config-registry-runtime* installed-config-registry-runtime)))

(defn- config-registry-state-snapshot []
	@(config-registry-state-atom))

(defn get-descriptor-registry
	"Return the full descriptor registry map."
	[]
	(:descriptor-registry (config-registry-state-snapshot)))

(defn get-value-registry
	"Return the full runtime config value registry map."
	[]
	(:value-registry (config-registry-state-snapshot)))

(defn set-descriptor-registry!
	"Replace the full descriptor registry map. Primarily for tests."
	[registry]
	(swap! (config-registry-state-atom) assoc :descriptor-registry (or registry {}))
	nil)

(defn set-value-registry!
	"Replace the full value registry map. Primarily for tests."
	[registry]
	(swap! (config-registry-state-atom) assoc :value-registry (or registry {}))
	nil)

(defn- normalize-descriptors
	[descriptors]
	(let [descriptors (vec descriptors)
				keys (mapv :key descriptors)]
		(when-not (every? keyword? keys)
			(throw (ex-info "Config descriptor keys must be keywords"
											{:descriptor-keys keys})))
		(when-not (= (count keys) (count (distinct keys)))
			(throw (ex-info "Duplicate config descriptor keys"
											{:descriptor-keys keys})))
		descriptors))

(defn register-config-descriptors!
	"Register all descriptors for a config domain.

	`domain` is a keyword such as `:gameplay/topology`.
	`descriptors` is a seq of pure maps describing config entries."
	[domain descriptors]
	(when-not (keyword? domain)
		(throw (ex-info "Config domain must be a keyword" {:domain domain})))
	(let [descriptors' (normalize-descriptors descriptors)]
		(swap! (config-registry-state-atom) assoc-in [:descriptor-registry domain] descriptors')
		(log-info "Registered config descriptors for" domain "count=" (count descriptors')))
	nil)

(defn get-config-descriptors
	[domain]
	(get-in (config-registry-state-snapshot) [:descriptor-registry domain] []))

(defn get-all-config-domains
	[]
	(keys (:descriptor-registry (config-registry-state-snapshot))))

(defn descriptor-default-values
	[domain]
	(into {}
				(map (juxt :key :default) (get-config-descriptors domain))))

(defn ensure-default-values!
	"Seed runtime values with defaults when a domain has not been populated yet."
	[domain defaults]
	(swap! (config-registry-state-atom) update-in [:value-registry domain]
				 (fn [existing]
					 (merge defaults existing)))
	nil)

(defn set-config-values!
	"Replace runtime values for a domain, preserving descriptor defaults for missing keys."
	[domain values]
	(let [defaults (descriptor-default-values domain)]
		(swap! (config-registry-state-atom) assoc-in [:value-registry domain] (merge defaults values)))
	nil)

(defn get-config-values
	[domain]
	(merge (descriptor-default-values domain)
				 (get-in (config-registry-state-snapshot) [:value-registry domain] {})))

(defn get-config-value
	([domain key]
	 (get (get-config-values domain) key))
	([domain key default]
	 (get (get-config-values domain) key default)))