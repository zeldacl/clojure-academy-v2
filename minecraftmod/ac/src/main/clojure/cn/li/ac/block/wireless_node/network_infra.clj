(ns cn.li.ac.block.wireless-node.network-infra
	"Infrastructure accessors for wireless node GUI network handlers.

	Isolates world/tile/capability/data access from message routing."
	(:require [cn.li.mcmod.platform.be :as platform-be]
						[cn.li.ac.wireless.search-config :as search-config]
						[cn.li.ac.wireless.core.vblock :as vb]
						[cn.li.ac.wireless.api :as helper]
						[cn.li.ac.wireless.data.world :as world-data]
						[cn.li.ac.wireless.data.network :as wireless-net]
						[cn.li.ac.wireless.gui.sync.handler :as net-helpers])
	(:import [cn.li.acapi.wireless IWirelessNode IWirelessMatrix WirelessCapabilityKeys]))

(defn resolve-world-tile
	[payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)]
		{:world world :tile tile}))

(defn linked-network
	[tile]
	(try
		(helper/get-wireless-net-by-node tile)
		(catch Exception _ nil)))

(defn node-range
	[tile]
	(try
		(.getRange ^IWirelessNode tile)
		(catch Exception _ 20.0)))

(defn available-networks
	[world x y z range]
	(helper/get-nets-in-range world x y z range (search-config/max-results)))

(defn matrix-capability
	[world net]
	(let [matrix (when (:matrix net)
						 (vb/vblock-get (:matrix net) world))]
		(when matrix
			(platform-be/get-capability matrix WirelessCapabilityKeys/MATRIX))))

(defn matrix-capacity
	[matrix-cap]
	(if matrix-cap
		(try (.getMatrixCapacity ^IWirelessMatrix matrix-cap) (catch Exception _ 0))
		0))

(defn matrix-bandwidth
	[matrix-cap]
	(if matrix-cap
		(try (.getMatrixBandwidth ^IWirelessMatrix matrix-cap) (catch Exception _ 0))
		0))

(defn matrix-range
	[matrix-cap]
	(if matrix-cap
		(try (.getMatrixRange ^IWirelessMatrix matrix-cap) (catch Exception _ 0.0))
		0.0))

(defn connect-node!
	[world tile ssid password]
	(let [world-state (world-data/get-world-data world)
				net (world-data/get-network-by-ssid world-state ssid)
				matrix (when net (vb/vblock-get (:matrix net) world))]
		(if (and net matrix)
			(boolean (wireless-net/add-node! net (vb/create-vnode tile) password))
			false)))

(defn disconnect-node!
	[tile]
	(helper/unlink-node-from-network! tile)
	true)