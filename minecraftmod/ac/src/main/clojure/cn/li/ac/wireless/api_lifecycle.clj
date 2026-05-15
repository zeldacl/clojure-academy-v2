(ns cn.li.ac.wireless.api-lifecycle
	"Lifecycle and diagnostics APIs for wireless system."
	(:require [cn.li.ac.wireless.service.world-registry :as world-registry]))

(defn tick-wireless-system!
	[world]
	(when-let [world-data (world-registry/get-world-data-non-create world)]
		(world-registry/tick-world-data! world-data)))

(defn save-wireless-data
	[world]
	(when-let [world-data (world-registry/get-world-data-non-create world)]
		(world-registry/world-data-to-nbt world-data)))

(defn load-wireless-data
	[world nbt]
	(world-registry/world-data-from-nbt world nbt))

(defn print-wireless-stats
	[world]
	(when-let [world-data (world-registry/get-world-data-non-create world)]
		(world-registry/print-statistics world-data)))

(defn get-all-networks
	[world]
	(when-let [world-data (world-registry/get-world-data-non-create world)]
		@(:networks world-data)))

(defn get-all-connections
	[world]
	(when-let [world-data (world-registry/get-world-data-non-create world)]
		@(:connections world-data)))