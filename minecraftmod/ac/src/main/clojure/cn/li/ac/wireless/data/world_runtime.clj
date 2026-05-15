(ns cn.li.ac.wireless.data.world-runtime
	(:require [cn.li.ac.wireless.core.vblock :as vb]
						[cn.li.ac.wireless.data.network-runtime :as network-runtime]
						[cn.li.ac.wireless.data.node-conn :as node-conn]
						[cn.li.ac.wireless.data.world-topology :as topology]
						[cn.li.mcmod.util.log :as log]))

(defn network-impl-validator
	"Remove disposed/invalid networks from world-data."
	[world-data]
	(doseq [item @(:networks world-data)]
		(when (or @(:disposed item)
							(and (vb/is-chunk-loaded? (:matrix item) (:world world-data))
									 (nil? (vb/vblock-get (:matrix item) (:world world-data)))))
			(topology/destroy-network-impl! world-data item))))

(defn node-connection-impl-validator
	"Remove disposed/invalid node connections from world-data."
	[world-data]
	(doseq [item @(:connections world-data)]
		(when (or @(:disposed item)
							(and (vb/is-chunk-loaded? (:node item) (:world world-data))
									 (nil? (vb/vblock-get (:node item) (:world world-data)))))
			(topology/destroy-node-connection-impl! world-data item))))

(defn tick-world-data!
	"Tick all world wireless items."
	[world-data]
	(doseq [item @(:networks world-data)]
		(when-not @(:disposed item)
			(network-runtime/tick-wireless-net! item)))
	(doseq [item @(:connections world-data)]
		(when-not @(:disposed item)
			(node-conn/tick-node-conn! item))))

(defn get-statistics
	"Get statistics about this world's wireless system."
	[world-data]
	{:networks (count @(:networks world-data))
	 :connections (count @(:connections world-data))
	 :net-lookups (count @(:net-lookup world-data))
	 :node-lookups (count @(:node-lookup world-data))})

(defn print-statistics
	"Print statistics to log."
	[world-data]
	(let [stats (get-statistics world-data)]
		(log/info "=== Wireless System Statistics ===")
		(log/info (format "Networks: %d" (:networks stats)))
		(log/info (format "Connections: %d" (:connections stats)))
		(log/info (format "Network lookups: %d" (:net-lookups stats)))
		(log/info (format "Node lookups: %d" (:node-lookups stats)))))