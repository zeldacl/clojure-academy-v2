(ns cn.li.ac.wireless.api-command
	"Mutating/command APIs for wireless topology operations."
	(:require [cn.li.ac.wireless.service.world-registry :as world-registry]
						[cn.li.ac.wireless.service.network-command :as network-command]
					[cn.li.ac.wireless.api-query :as api-query]
						[cn.li.ac.wireless.core.vblock :as vb]
						[cn.li.mcmod.platform.be :as platform-be]
						[cn.li.mcmod.platform.events :as platform-events])
	(:import [cn.li.acapi.wireless IWirelessNode IWirelessMatrix IWirelessGenerator IWirelessReceiver WirelessCapabilityKeys]))

(defn- tile-level
	[tile]
	(platform-be/be-get-world-safe tile))

(defn- get-cap
	[tile cap-key]
	(try (platform-be/get-capability tile cap-key)
			 (catch Exception _ nil)))

(defn- get-wireless-net-by-matrix
	[matrix-tile]
	(api-query/lookup-wireless-net-by-matrix matrix-tile))

(defn- get-wireless-net-by-node
	[node-tile]
	(api-query/lookup-wireless-net-by-node node-tile))

(defn- get-node-conn-by-generator
	[gen-tile]
	(let [world (platform-be/be-get-world-safe gen-tile)
				world-data (world-registry/get-world-data world)
				gen-vb (vb/create-vgenerator gen-tile)]
		(world-registry/get-node-connection world-data gen-vb)))

(defn- get-node-conn-by-receiver
	[rec-tile]
	(let [world (platform-be/be-get-world-safe rec-tile)
				world-data (world-registry/get-world-data world)
				rec-vb (vb/create-vreceiver rec-tile)]
		(world-registry/get-node-connection world-data rec-vb)))

(defn create-network!
	[matrix-tile ssid password]
	(let [world (platform-be/be-get-world-safe matrix-tile)
				world-data (world-registry/get-world-data world)
				matrix-vb (vb/create-vmatrix matrix-tile)
				created? (network-command/create-network! world-data matrix-vb ssid password)]
		(when created?
			(when-let [matrix-cap (get-cap matrix-tile WirelessCapabilityKeys/MATRIX)]
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
					ssid (:ssid network-item)
					destroyed? (network-command/destroy-network! world-data network-item)]
			(when destroyed?
				(when-let [matrix-cap (get-cap matrix-tile WirelessCapabilityKeys/MATRIX)]
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
				(when-let [matrix-cap (get-cap matrix-tile WirelessCapabilityKeys/MATRIX)]
					(when-let [node-cap (get-cap node-tile WirelessCapabilityKeys/NODE)]
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
				(when-let [node-cap (get-cap node-tile WirelessCapabilityKeys/NODE)]
					(when-let [matrix-cap (some-> matrix-tile (get-cap WirelessCapabilityKeys/MATRIX))]
						(platform-events/fire-event!
							{:kind :topology/node
							 :action :disconnected
							 :matrix ^IWirelessMatrix matrix-cap
							 :node ^IWirelessNode node-cap}))))
			removed?)))

(defn link-generator-to-node!
	[gen-tile node-tile password need-auth]
	(when-let [node-cap (get-cap node-tile WirelessCapabilityKeys/NODE)]
		(when (or (not need-auth)
							(= password (.getPassword ^IWirelessNode node-cap)))
			(let [world (platform-be/be-get-world-safe node-tile)
						world-data (world-registry/get-world-data world)
						node-vb (vb/create-vnode-conn node-tile)
						conn (network-command/ensure-node-connection! world-data node-vb)
						gen-vb (vb/create-vgenerator gen-tile)
						linked? (network-command/link-generator-to-connection! world-data conn gen-vb)]
				(when linked?
					(when-let [gen-cap (get-cap gen-tile WirelessCapabilityKeys/GENERATOR)]
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
	(when-let [node-cap (get-cap node-tile WirelessCapabilityKeys/NODE)]
		(when (or (not need-auth)
							(= password (.getPassword ^IWirelessNode node-cap)))
			(let [world (platform-be/be-get-world-safe node-tile)
						world-data (world-registry/get-world-data world)
						node-vb (vb/create-vnode-conn node-tile)
						conn (network-command/ensure-node-connection! world-data node-vb)
						rec-vb (vb/create-vreceiver rec-tile)
						linked? (network-command/link-receiver-to-connection! world-data conn rec-vb)]
				(when linked?
					(when-let [rec-cap (get-cap rec-tile WirelessCapabilityKeys/RECEIVER)]
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