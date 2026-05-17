(ns cn.li.ac.block.wireless-node.network-infra
	"Infrastructure accessors for wireless node GUI network handlers.

	Isolates world/tile/capability/data access from message routing."
	(:require [cn.li.ac.wireless.config :as search-config]
						[cn.li.ac.wireless.core.capability-resolver :as resolver]
						[cn.li.ac.wireless.api :as wireless-api]
						[cn.li.ac.wireless.gui.sync.handler :as net-helpers])
	(:import [cn.li.acapi.wireless IWirelessNode IWirelessMatrix]))

(defn resolve-world-tile
	[payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)]
		{:world world :tile tile}))

(defn linked-network
	[tile]
	(try
		(wireless-api/get-wireless-net-by-node tile)
		(catch Exception _ nil)))

(defn node-range
	[tile]
	(try
		(.getRange ^IWirelessNode tile)
		(catch Exception _ 20.0)))

(defn available-networks
	[world x y z range]
	(wireless-api/get-nets-in-range world x y z range (search-config/max-results)))

(defn matrix-capability
	[world net]
	(when (:matrix net)
		(resolver/resolve-matrix-cap world (:matrix net))))

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
	(wireless-api/connect-node-to-ssid! world tile ssid password))

(defn disconnect-node!
	[tile]
	(wireless-api/unlink-node-from-network! tile)
	true)