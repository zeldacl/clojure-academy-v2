(ns cn.li.mcmod.config.registry
	"Platform-neutral config descriptor and runtime value registry."
	(:require [cn.li.mcmod.util.log :as log]))

(defonce descriptor-registry
	(atom {}))

(defonce value-registry
	(atom {}))

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

	`domain` is a keyword such as `:cn.li.ac/wireless`.
	`descriptors` is a seq of pure maps describing config entries."
	[domain descriptors]
	(when-not (keyword? domain)
		(throw (ex-info "Config domain must be a keyword" {:domain domain})))
	(let [descriptors' (normalize-descriptors descriptors)]
		(swap! descriptor-registry assoc domain descriptors')
		(log/info "Registered config descriptors for" domain "count=" (count descriptors')))
	nil)

(defn get-config-descriptors
	[domain]
	(get @descriptor-registry domain []))

(defn get-all-config-domains
	[]
	(keys @descriptor-registry))

(defn descriptor-default-values
	[domain]
	(into {}
				(map (juxt :key :default) (get-config-descriptors domain))))

(defn ensure-default-values!
	"Seed runtime values with defaults when a domain has not been populated yet."
	[domain defaults]
	(swap! value-registry update domain
				 (fn [existing]
					 (merge defaults existing)))
	nil)

(defn set-config-values!
	"Replace runtime values for a domain, preserving descriptor defaults for missing keys."
	[domain values]
	(let [defaults (descriptor-default-values domain)]
		(swap! value-registry assoc domain (merge defaults values)))
	nil)

(defn get-config-values
	[domain]
	(merge (descriptor-default-values domain)
				 (get @value-registry domain {})))

(defn get-config-value
	([domain key]
	 (get (get-config-values domain) key))
	([domain key default]
	 (get (get-config-values domain) key default)))