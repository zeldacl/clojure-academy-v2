(ns cn.li.ac.block.wireless-node.config
	(:require [cn.li.ac.config.common :as config-common]
						[cn.li.mcmod.config.registry :as config-reg]))

(def descriptors
	[{:key :node-basic-max-energy
		:section :node.basic
		:path "node.basic.max-energy"
		:type :int
		:default 15000
		:min 0
		:max 100000000
		:comment "Basic node max energy storage."}
	 {:key :node-basic-bandwidth
		:section :node.basic
		:path "node.basic.bandwidth"
		:type :int
		:default 150
		:min 0
		:max 1000000
		:comment "Basic node transfer bandwidth."}
	 {:key :node-basic-range
		:section :node.basic
		:path "node.basic.range"
		:type :double
		:default 9.0
		:min 0.0
		:max 4096.0
		:comment "Basic node wireless range in blocks."}
	 {:key :node-basic-capacity
		:section :node.basic
		:path "node.basic.capacity"
		:type :int
		:default 5
		:min 0
		:max 1024
		:comment "Basic node connection capacity."}
	 {:key :node-standard-max-energy
		:section :node.standard
		:path "node.standard.max-energy"
		:type :int
		:default 50000
		:min 0
		:max 100000000
		:comment "Standard node max energy storage."}
	 {:key :node-standard-bandwidth
		:section :node.standard
		:path "node.standard.bandwidth"
		:type :int
		:default 300
		:min 0
		:max 1000000
		:comment "Standard node transfer bandwidth."}
	 {:key :node-standard-range
		:section :node.standard
		:path "node.standard.range"
		:type :double
		:default 12.0
		:min 0.0
		:max 4096.0
		:comment "Standard node wireless range in blocks."}
	 {:key :node-standard-capacity
		:section :node.standard
		:path "node.standard.capacity"
		:type :int
		:default 10
		:min 0
		:max 1024
		:comment "Standard node connection capacity."}
	 {:key :node-advanced-max-energy
		:section :node.advanced
		:path "node.advanced.max-energy"
		:type :int
		:default 200000
		:min 0
		:max 100000000
		:comment "Advanced node max energy storage."}
	 {:key :node-advanced-bandwidth
		:section :node.advanced
		:path "node.advanced.bandwidth"
		:type :int
		:default 900
		:min 0
		:max 1000000
		:comment "Advanced node transfer bandwidth."}
	 {:key :node-advanced-range
		:section :node.advanced
		:path "node.advanced.range"
		:type :double
		:default 19.0
		:min 0.0
		:max 4096.0
		:comment "Advanced node wireless range in blocks."}
	 {:key :node-advanced-capacity
		:section :node.advanced
		:path "node.advanced.capacity"
		:type :int
		:default 20
		:min 0
		:max 1024
		:comment "Advanced node connection capacity."}
	 {:key :node-sync-interval
		:section :tick
		:path "tick.node-sync-interval"
		:type :int
		:default 20
		:min 1
		:max 1200
		:comment "Ticks between node network check and GUI sync passes."}])

(def default-values
	(into {} (map (juxt :key :default) descriptors)))

(defn- cfg []
	(merge default-values
				 (config-reg/get-config-values config-common/wireless-domain)))

(defn- tier-key
	[tier suffix]
	(keyword (str "node-" (name tier) "-" suffix)))

(defn max-energy [tier]
	(get (cfg) (tier-key tier "max-energy")))

(defn bandwidth [tier]
	(get (cfg) (tier-key tier "bandwidth")))

(defn range-blocks [tier]
	(get (cfg) (tier-key tier "range")))

(defn capacity [tier]
	(get (cfg) (tier-key tier "capacity")))

(defn node-config [tier]
	{:max-energy (max-energy tier)
	 :bandwidth (bandwidth tier)
	 :range (range-blocks tier)
	 :capacity (capacity tier)})

(defn node-types []
	{:basic (node-config :basic)
	 :standard (node-config :standard)
	 :advanced (node-config :advanced)})

(defn sync-interval []
	(:node-sync-interval (cfg)))