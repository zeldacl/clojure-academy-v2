(ns cn.li.ac.wireless.data.network-lookup
	"Network and node-connection lookup helpers over WiWorldData."
	(:require [cn.li.ac.wireless.core.vblock :as vb]
						[cn.li.ac.wireless.data.world-registry :as world-registry]
						[cn.li.mcmod.util.log :as log]))

(defn get-network-by-matrix
	"Get network by matrix vblock."
	[world-data matrix-vblock]
	(get (world-registry/net-lookup world-data) matrix-vblock))

(defn get-network-by-node
	"Get network by node vblock."
	[world-data node-vblock]
	(get (world-registry/net-lookup world-data) node-vblock))

(defn get-network-by-ssid
	"Get network by SSID string."
	[world-data ssid]
	(get (world-registry/net-lookup world-data) ssid))

(defn get-node-connection
	"Get node connection by node/generator/receiver vblock."
	[world-data vblock]
	(get (world-registry/node-lookup world-data) vblock))

(defn range-search-networks
	"Search for networks whose matrix is within range of (x,y,z).

	Iterates over all networks directly and filters by the distance between the
	search point and each network's matrix vblock. This is O(n) in the number of
	networks (typically <100) and avoids spatial-index desync bugs."
	[world-data x y z search-radius max-results]
	(let [all-nets (world-registry/networks world-data)
				range-sq (* search-radius search-radius)]
		(log/info "[range-search-networks] world-data" (pr-str (:world-key world-data))
							"total-networks" (count all-nets)
							"search-pos" [x y z]
							"radius" search-radius)
		(doseq [net all-nets]
			(let [mv (:matrix net)]
				(log/info "[range-search-networks] net ssid=" (pr-str (try (:ssid (:state net)) (catch Exception _ "?")))
									"disposed=" (try (:disposed (:state net)) (catch Exception _ "?"))
									"matrix-pos=" (when mv [(:x mv) (:y mv) (:z mv)])
									"dist-sq=" (when mv (vb/dist-sq-pos mv x y z))
									"range-sq=" range-sq)))
		(->> all-nets
				 (filter (fn [net]
									 (when-let [matrix-vb (:matrix net)]
										 (<= (vb/dist-sq-pos matrix-vb x y z) range-sq))))
				 (take max-results))))