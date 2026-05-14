(ns cn.li.ac.wireless.api-query
	"Read-only/query APIs for wireless system."
	(:require [cn.li.ac.wireless.data.world :as wd]
						[cn.li.ac.wireless.search-config :as search-config]
						[cn.li.ac.wireless.core.vblock :as vb]
						[cn.li.ac.wireless.data.node-conn :as node-conn]
						[cn.li.mcmod.platform.be :as platform-be]
						[cn.li.mcmod.platform.position :as pos]))

(defn- tile-level
	[tile]
	(platform-be/be-get-world-safe tile))

(defn get-wireless-net-by-matrix
	[matrix-tile]
	(let [world (tile-level matrix-tile)
				world-data (wd/get-world-data world)
				matrix-vb (vb/create-vmatrix matrix-tile)]
		(wd/get-network-by-matrix world-data matrix-vb)))

(defn get-wireless-net-by-node
	[node-tile]
	(let [world (tile-level node-tile)
				world-data (wd/get-world-data world)
				node-vb (vb/create-vnode node-tile)]
		(wd/get-network-by-node world-data node-vb)))

(defn lookup-wireless-net-by-matrix
	"Compatibility lookup entry for matrix -> wireless network.

	Prefers facade symbol override when present (for tests/compat hooks),
	otherwise falls back to local query implementation."
	[matrix-tile]
	(if-let [lookup-fn (try
								(requiring-resolve 'cn.li.ac.wireless.api/get-wireless-net-by-matrix)
								(catch Exception _ nil))]
		(lookup-fn matrix-tile)
		(get-wireless-net-by-matrix matrix-tile)))

(defn lookup-wireless-net-by-node
	"Compatibility lookup entry for node -> wireless network.

	Prefers facade symbol override when present (for tests/compat hooks),
	otherwise falls back to local query implementation."
	[node-tile]
	(if-let [lookup-fn (try
								(requiring-resolve 'cn.li.ac.wireless.api/get-wireless-net-by-node)
								(catch Exception _ nil))]
		(lookup-fn node-tile)
		(get-wireless-net-by-node node-tile)))

(defn is-node-linked?
	[node-tile]
	(some? (lookup-wireless-net-by-node node-tile)))

(defn is-matrix-active?
	[matrix-tile]
	(some? (get-wireless-net-by-matrix matrix-tile)))

(defn get-nets-in-range
	[world x y z range max-results]
	(let [world-data (wd/get-world-data world)]
		(wd/range-search-networks world-data x y z range max-results)))

(defn get-node-conn-by-node
	[node-tile]
	(let [world (platform-be/be-get-world-safe node-tile)
				world-data (wd/get-world-data world)
				node-vb (vb/create-vnode-conn node-tile)]
		(wd/get-node-connection world-data node-vb)))

(defn get-node-conn-by-generator
	[gen-tile]
	(let [world (platform-be/be-get-world-safe gen-tile)
				world-data (wd/get-world-data world)
				gen-vb (vb/create-vgenerator gen-tile)]
		(wd/get-node-connection world-data gen-vb)))

(defn get-node-conn-by-receiver
	[rec-tile]
	(let [world (platform-be/be-get-world-safe rec-tile)
				world-data (wd/get-world-data world)
				rec-vb (vb/create-vreceiver rec-tile)]
		(wd/get-node-connection world-data rec-vb)))

(defn is-receiver-linked?
	[rec-tile]
	(some? (get-node-conn-by-receiver rec-tile)))

(defn is-generator-linked?
	[gen-tile]
	(some? (get-node-conn-by-generator gen-tile)))

(defn get-nodes-in-range-at
	[world x y z]
	(let [search-range (search-config/node-search-range)
				max-results (search-config/max-results)
				world-data (wd/get-world-data world)
				nearby-chunks (wd/get-nearby-chunks x y z search-range)
				candidate-vblocks (wd/get-vblocks-in-chunks world-data nearby-chunks)
				range-sq (* search-range search-range)
				matching-nodes
				(reduce
					(fn [acc node-vb]
						(let [dist-sq (vb/dist-sq-pos node-vb x y z)]
							(if (<= dist-sq range-sq)
								(if-let [node (vb/vblock-get node-vb world)]
									(let [node-range (.getRange ^cn.li.acapi.wireless.IWirelessNode node)
												node-range-sq (* node-range node-range)]
										(if (<= dist-sq node-range-sq)
											(if-let [conn (wd/get-node-connection world-data node-vb)]
												(if (< (node-conn/get-load conn)
															 (node-conn/get-capacity conn))
													(conj acc node)
													acc)
												(conj acc node))
											acc))
									acc)
								acc)))
					[]
					candidate-vblocks)]
		(take max-results matching-nodes)))

(defn get-nodes-in-range
	[world pos]
	(get-nodes-in-range-at world (pos/pos-x pos) (pos/pos-y pos) (pos/pos-z pos)))