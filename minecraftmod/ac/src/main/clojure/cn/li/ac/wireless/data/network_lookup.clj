(ns cn.li.ac.wireless.data.network-lookup
	"Network and node-connection lookup helpers over WiWorldData."
	(:require [cn.li.ac.wireless.core.vblock :as vb]
						[cn.li.ac.wireless.data.spatial-lookup :as spatial]))

(defn get-network-by-matrix
	"Get network by matrix vblock."
	[world-data matrix-vblock]
	(get @(:net-lookup world-data) matrix-vblock))

(defn get-network-by-node
	"Get network by node vblock."
	[world-data node-vblock]
	(get @(:net-lookup world-data) node-vblock))

(defn get-network-by-ssid
	"Get network by SSID string."
	[world-data ssid]
	(get @(:net-lookup world-data) ssid))

(defn get-node-connection
	"Get node connection by node/generator/receiver vblock."
	[world-data vblock]
	(get @(:node-lookup world-data) vblock))

(defn range-search-networks
	"Search for networks within range of coordinates using the spatial index."
	[world-data x y z search-radius max-results]
	(let [range-sq (* search-radius search-radius)
				chunk-keys (spatial/get-nearby-chunks x y z search-radius)
				candidate-vblocks (spatial/get-vblocks-in-chunks world-data chunk-keys)
				net-lookup @(:net-lookup world-data)]
		(->> candidate-vblocks
				 (keep (fn [vblock]
								 (when-let [net (get net-lookup vblock)]
									 (when (= vblock (:matrix net))
										 (when (<= (vb/dist-sq-pos vblock x y z) range-sq)
											 net)))))
				 (distinct)
				 (take max-results))))