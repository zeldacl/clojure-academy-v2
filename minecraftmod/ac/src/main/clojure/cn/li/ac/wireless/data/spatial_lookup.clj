(ns cn.li.ac.wireless.data.spatial-lookup
	"Spatial-index helpers for wireless world data."
	(:require [cn.li.ac.wireless.core.spatial-index :as si]
						[cn.li.ac.wireless.data.world-registry :as world-registry]))

(defn add-to-spatial-index!
	"Add a vblock to the spatial index."
	[world-data vblock]
	(world-registry/update-state-value! world-data :spatial-index si/add-to-index vblock))

(defn remove-from-spatial-index!
	"Remove a vblock from the spatial index."
	[world-data vblock]
	(world-registry/update-state-value! world-data :spatial-index si/remove-from-index vblock))

(defn get-nearby-chunks
	"Get chunk keys within range of a position (delegates to spatial-index)."
	[x y z search-radius]
	(si/nearby-chunk-keys x y z search-radius))

(defn get-vblocks-in-chunks
	"Get all vblocks in the specified chunks."
	[world-data chunk-keys]
	(si/vblocks-in-index (world-registry/spatial-index world-data) chunk-keys))