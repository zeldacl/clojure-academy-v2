(ns cn.li.ac.wireless.data.world-runtime
	(:require [cn.li.ac.wireless.core.vblock :as vb]
						[cn.li.ac.wireless.core.capability-resolver :as resolver]
						[cn.li.ac.wireless.data.network-runtime :as network-runtime]
						[cn.li.ac.wireless.data.network-state :as network-state]
						[cn.li.ac.wireless.data.node-conn :as node-conn]
						[cn.li.ac.wireless.data.world-registry :as world-registry]
						[cn.li.ac.wireless.service.commands :as commands]
						[cn.li.mcmod.util.log :as log]))

(defn network-impl-validator
	"Remove disposed/invalid networks from world-data."
	[world-data]
	(doseq [item (world-registry/networks world-data)]
		(when (or (network-state/is-disposed? item)
							(and (vb/is-chunk-loaded? (:matrix item) (:world world-data))
									 (nil? (resolver/resolve-matrix-cap (:world world-data) (:matrix item)))))
			(commands/destroy-network! world-data item))))

(defn node-connection-impl-validator
	"Remove disposed/invalid node connections from world-data."
	[world-data]
		(doseq [item (world-registry/connections world-data)]
			(when (node-conn/is-disposed? item)
				(commands/destroy-node-connection! world-data item))))
(defn tick-world-data!
	"Tick all world wireless items."
	[world-data]
	(doseq [item (world-registry/networks world-data)]
		(when (network-state/active? item)
			(network-runtime/tick-wireless-net! item)))
	(doseq [item (world-registry/connections world-data)]
		(when-not (node-conn/is-disposed? item)
			(node-conn/tick-node-conn! item))))

(defn get-statistics
	"Get statistics about this world's wireless system."
	[world-data]
	{:networks (count (world-registry/networks world-data))
	 :connections (count (world-registry/connections world-data))
	 :net-lookups (count (world-registry/net-lookup world-data))
	 :node-lookups (count (world-registry/node-lookup world-data))})

(defn print-statistics
	"Print statistics to log."
	[world-data]
	(let [stats (get-statistics world-data)]
		(log/info "=== Wireless System Statistics ===")
		(log/info (format "Networks: %d" (:networks stats)))
		(log/info (format "Connections: %d" (:connections stats)))
		(log/info (format "Network lookups: %d" (:net-lookups stats)))
		(log/info (format "Node lookups: %d" (:node-lookups stats)))))