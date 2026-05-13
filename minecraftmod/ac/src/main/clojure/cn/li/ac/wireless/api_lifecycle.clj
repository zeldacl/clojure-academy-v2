(ns cn.li.ac.wireless.api-lifecycle
	"Lifecycle and diagnostics APIs for wireless system."
	(:require [cn.li.ac.wireless.data.world :as wd]))

(defn tick-wireless-system!
	[world]
	(when-let [world-data (wd/get-world-data-non-create world)]
		(wd/tick-world-data! world-data)))

(defn save-wireless-data
	[world]
	(when-let [world-data (wd/get-world-data-non-create world)]
		(wd/world-data-to-nbt world-data)))

(defn load-wireless-data
	[world nbt]
	(wd/world-data-from-nbt world nbt))

(defn print-wireless-stats
	[world]
	(when-let [world-data (wd/get-world-data-non-create world)]
		(wd/print-statistics world-data)))

(defn get-all-networks
	[world]
	(when-let [world-data (wd/get-world-data-non-create world)]
		@(:networks world-data)))

(defn get-all-connections
	[world]
	(when-let [world-data (wd/get-world-data-non-create world)]
		@(:connections world-data)))