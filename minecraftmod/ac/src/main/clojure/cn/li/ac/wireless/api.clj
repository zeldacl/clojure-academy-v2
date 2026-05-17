(ns cn.li.ac.wireless.api
	"Canonical public API for wireless queries and topology commands."
	(:require [cn.li.ac.wireless.core.vblock :as vb]
					[cn.li.ac.wireless.core.capability-resolver :as resolver]
					[cn.li.ac.wireless.data.network-state :as network-state]
					[cn.li.ac.wireless.config :as wireless-config]
					[cn.li.ac.wireless.service.network-command :as network-command]
					[cn.li.ac.wireless.service.node-connection :as node-connection]
					[cn.li.ac.wireless.service.world-registry :as world-registry]
					[cn.li.mcmod.platform.be :as platform-be]
					[cn.li.mcmod.platform.events :as platform-events]
					[cn.li.mcmod.platform.position :as pos])
	(:import [cn.li.acapi.wireless
						IWirelessGenerator
						IWirelessMatrix
						IWirelessNode
						IWirelessReceiver]))

(def network-snapshot network-state/snapshot)
(def network-ssid network-state/get-ssid)
(def network-password network-state/get-password)
(def network-load network-state/get-load)
(def network-active? network-state/active?)

(defn- tile-level
	[tile]
	(platform-be/be-get-world-safe tile))

(defn get-wireless-net-by-matrix
	[matrix-tile]
	(let [world (tile-level matrix-tile)
				world-data (world-registry/get-world-data world)
				matrix-vb (vb/create-vmatrix matrix-tile)]
		(world-registry/get-network-by-matrix world-data matrix-vb)))

(defn get-wireless-net-by-node
	[node-tile]
	(let [world (tile-level node-tile)
				world-data (world-registry/get-world-data world)
				node-vb (vb/create-vnode node-tile)]
		(world-registry/get-network-by-node world-data node-vb)))

(defn get-wireless-net-by-ssid
	[world ssid]
	(let [world-data (world-registry/get-world-data world)]
		(world-registry/get-network-by-ssid world-data ssid)))

(defn is-node-linked?
	[node-tile]
	(some? (get-wireless-net-by-node node-tile)))

(defn is-matrix-active?
	[matrix-tile]
	(some? (get-wireless-net-by-matrix matrix-tile)))

(defn get-nets-in-range
	[world x y z range max-results]
	(let [world-data (world-registry/get-world-data world)]
		(world-registry/range-search-networks world-data x y z range max-results)))

(defn get-node-conn-by-node
	[node-tile]
	(let [world (platform-be/be-get-world-safe node-tile)
				world-data (world-registry/get-world-data world)
				node-vb (vb/create-vnode-conn node-tile)]
		(world-registry/get-node-connection world-data node-vb)))

(defn get-node-conn-by-generator
	[gen-tile]
	(let [world (platform-be/be-get-world-safe gen-tile)
				world-data (world-registry/get-world-data world)
				gen-vb (vb/create-vgenerator gen-tile)]
		(world-registry/get-node-connection world-data gen-vb)))

(defn get-node-conn-by-receiver
	[rec-tile]
	(let [world (platform-be/be-get-world-safe rec-tile)
				world-data (world-registry/get-world-data world)
				rec-vb (vb/create-vreceiver rec-tile)]
		(world-registry/get-node-connection world-data rec-vb)))

(defn is-receiver-linked?
	[rec-tile]
	(some? (get-node-conn-by-receiver rec-tile)))

(defn is-generator-linked?
	[gen-tile]
	(some? (get-node-conn-by-generator gen-tile)))

(defn get-nodes-in-range-at
	[world x y z]
	(let [search-range (wireless-config/node-search-range)
				max-results (wireless-config/max-results)
				world-data (world-registry/get-world-data world)
				nearby-chunks (world-registry/get-nearby-chunks x y z search-range)
				candidate-vblocks (world-registry/get-vblocks-in-chunks world-data nearby-chunks)
				range-sq (* search-range search-range)
				matching-nodes
				(reduce
					(fn [acc node-vb]
						(let [dist-sq (vb/dist-sq-pos node-vb x y z)]
							(if (<= dist-sq range-sq)
								(if-let [node (resolver/resolve-node-cap world node-vb)]
									(let [node-range (.getRange ^IWirelessNode node)
											node-range-sq (* node-range node-range)]
										(if (<= dist-sq node-range-sq)
											(if-let [conn (world-registry/get-node-connection world-data node-vb)]
												(if (< (node-connection/get-load conn)
															 (node-connection/get-capacity conn))
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

(defn create-network!
	[matrix-tile ssid password]
	(let [world (platform-be/be-get-world-safe matrix-tile)
				world-data (world-registry/get-world-data world)
				matrix-vb (vb/create-vmatrix matrix-tile)
				created? (network-command/create-network! world-data matrix-vb ssid password)]
		(when created?
				(when-let [matrix-cap (resolver/matrix-capability matrix-tile)]
				(platform-events/fire-event!
					{:kind :topology/network
					 :action :created
					 :ssid ssid
					 :matrix ^IWirelessMatrix matrix-cap})))
		created?))

(defn destroy-network!
	[matrix-tile]
	(when-let [network-item (get-wireless-net-by-matrix matrix-tile)]
		(let [world (platform-be/be-get-world-safe matrix-tile)
					world-data (world-registry/get-world-data world)
					ssid (network-state/get-ssid network-item)
					destroyed? (network-command/destroy-network! world-data network-item)]
			(when destroyed?
					(when-let [matrix-cap (resolver/matrix-capability matrix-tile)]
					(platform-events/fire-event!
						{:kind :topology/network
						 :action :destroyed
						 :ssid ssid
						 :matrix ^IWirelessMatrix matrix-cap})))
			destroyed?)))

(defn link-node-to-network!
	[node-tile matrix-tile password]
	(when-let [network-item (get-wireless-net-by-matrix matrix-tile)]
		(let [world (platform-be/be-get-world-safe matrix-tile)
					world-data (world-registry/get-world-data world)
					node-vb (vb/create-vnode node-tile)
					linked? (network-command/link-node-to-network! world-data network-item node-vb password)]
			(when linked?
				(when-let [matrix-cap (resolver/matrix-capability matrix-tile)]
					(when-let [node-cap (resolver/node-capability node-tile)]
						(platform-events/fire-event!
							{:kind :topology/node
							 :action :connected
							 :matrix ^IWirelessMatrix matrix-cap
							 :node ^IWirelessNode node-cap}))))
			linked?)))

(defn unlink-node-from-network!
	[node-tile]
	(when-let [network-item (get-wireless-net-by-node node-tile)]
		(let [world (platform-be/be-get-world-safe node-tile)
					matrix-tile (when-let [matrix-vb (:matrix network-item)]
										(vb/vblock-get matrix-vb world))
					node-vb (vb/create-vnode node-tile)
					removed? (network-command/unlink-node-from-network! network-item node-vb)]
			(when removed?
					(when-let [node-cap (resolver/node-capability node-tile)]
						(when-let [matrix-cap (some-> matrix-tile resolver/matrix-capability)]
						(platform-events/fire-event!
							{:kind :topology/node
							 :action :disconnected
							 :matrix ^IWirelessMatrix matrix-cap
							 :node ^IWirelessNode node-cap}))))
			removed?)))

(defn connect-node-to-ssid!
	[world node-tile ssid password]
	(let [network (get-wireless-net-by-ssid world ssid)
			matrix-tile (when-let [matrix-vb (:matrix network)]
								(vb/vblock-get matrix-vb world))]
		(boolean
			(when (and network matrix-tile)
				(link-node-to-network! node-tile matrix-tile password)))))

(defn link-generator-to-node!
	[gen-tile node-tile password need-auth]
	(when-let [node-cap (resolver/node-capability node-tile)]
		(when (or (not need-auth)
						(= password (.getPassword ^IWirelessNode node-cap)))
			(let [world (platform-be/be-get-world-safe node-tile)
						world-data (world-registry/get-world-data world)
						node-vb (vb/create-vnode-conn node-tile)
						conn (network-command/ensure-node-connection! world-data node-vb)
						gen-vb (vb/create-vgenerator gen-tile)
						linked? (network-command/link-generator-to-connection! world-data conn gen-vb)]
				(when linked?
						(when-let [gen-cap (resolver/generator-capability gen-tile)]
						(platform-events/fire-event!
							{:kind :topology/node
							 :action :generator-linked
							 :node ^IWirelessNode node-cap
							 :generator ^IWirelessGenerator gen-cap})))
				linked?))))

(defn unlink-generator-from-node!
	[gen-tile]
	(when-let [conn (get-node-conn-by-generator gen-tile)]
		(let [gen-vb (vb/create-vgenerator gen-tile)]
			(network-command/unlink-generator-from-connection! conn gen-vb))))

(defn link-receiver-to-node!
	[rec-tile node-tile password need-auth]
	(when-let [node-cap (resolver/node-capability node-tile)]
		(when (or (not need-auth)
						(= password (.getPassword ^IWirelessNode node-cap)))
			(let [world (platform-be/be-get-world-safe node-tile)
						world-data (world-registry/get-world-data world)
						node-vb (vb/create-vnode-conn node-tile)
						conn (network-command/ensure-node-connection! world-data node-vb)
						rec-vb (vb/create-vreceiver rec-tile)
						linked? (network-command/link-receiver-to-connection! world-data conn rec-vb)]
				(when linked?
						(when-let [rec-cap (resolver/receiver-capability rec-tile)]
						(platform-events/fire-event!
							{:kind :topology/node
							 :action :receiver-linked
							 :node ^IWirelessNode node-cap
							 :receiver ^IWirelessReceiver rec-cap})))
				linked?))))

(defn unlink-receiver-from-node!
	[rec-tile]
	(when-let [conn (get-node-conn-by-receiver rec-tile)]
		(let [rec-vb (vb/create-vreceiver rec-tile)]
			(network-command/unlink-receiver-from-connection! conn rec-vb))))

(defn change-network-ssid!
	[network new-ssid]
	(network-command/change-network-ssid! network new-ssid))

(defn change-network-password!
	[network new-password]
	(network-command/reset-network-password! network new-password))