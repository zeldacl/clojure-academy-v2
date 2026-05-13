(ns cn.li.ac.wireless.data.world-persistence
	"Persistence and lifecycle layer for wireless world data.

	Handles NBT serialization/deserialization and world lifecycle events
	(load, save, unload). The data registry itself lives in
	cn.li.ac.wireless.data.world."
	(:require [cn.li.ac.wireless.data.network-nbt :as network-nbt]
						[cn.li.ac.wireless.data.node-conn-nbt :as node-conn-nbt]
						[cn.li.ac.wireless.data.world :as wd]
						[cn.li.mcmod.events.world-lifecycle :as world-lifecycle]
						[cn.li.mcmod.platform.nbt :as nbt]
						[cn.li.mcmod.util.log :as log]))

;; Protocol for saved data wrapper to support Clojure deftype
(defprotocol IWiSavedData
	(get-wi-data [this] "Get the WiWorldData from the wrapper"))

(deftype WiSavedDataWrapper
	[^:volatile-mutable wi-data]
	IWiSavedData
	(get-wi-data [_]
		wi-data)
	Object
	(toString [_]
		(str "WiSavedDataWrapper["
				 (if wi-data (str (count @(:networks wi-data)) " networks") "uninitialized")
				 "]")))

(defn- nbt-write-list!
	[nbt-root tag items to-nbt-fn skip-fn]
	(let [lst (nbt/create-nbt-list)]
		(doseq [item items]
			(when-not (skip-fn item)
				(nbt/nbt-append! lst (to-nbt-fn item))))
		(nbt/nbt-set-tag! nbt-root tag lst)))

(defn- nbt-read-list
	[nbt-root tag from-nbt-fn world-data]
	(let [lst (nbt/nbt-get-list nbt-root tag)
				size (nbt/nbt-list-size lst)]
		(vec (for [i (range size)]
					 (from-nbt-fn world-data (nbt/nbt-list-get-compound lst i))))))

(defn world-data-to-nbt
	"Serialize world-data to NBT."
	[world-data]
	(let [out (nbt/create-nbt-compound)]
		(nbt-write-list! out "networks" @(:networks world-data) network-nbt/network-to-nbt (fn [net] @(:disposed net)))
		(nbt-write-list! out "connections" @(:connections world-data) node-conn-nbt/node-connection-to-nbt (fn [conn] @(:disposed conn)))
		out))

(defn world-data-from-nbt
	"Deserialize world-data from NBT and rebuild indexes."
	[world nbt-root]
	(let [world-data (wd/create-world-data world)
				networks (nbt-read-list nbt-root "networks" network-nbt/network-from-nbt world-data)
				connections (nbt-read-list nbt-root "connections" node-conn-nbt/node-connection-from-nbt world-data)]
		(reset! (:networks world-data) networks)
		(reset! (:connections world-data) connections)
		(doseq [net networks]
			(wd/rebuild-network-indexes! world-data net))
		(doseq [conn connections]
			(wd/rebuild-connection-indexes! world-data conn))
		world-data))

(defn get-saved-data-world-data
	"Extract WiWorldData from SavedData wrapper."
	[saved-data]
	(when saved-data
		(try
			(get-wi-data saved-data)
			(catch Exception _ nil))))

(defn on-world-load
	"Called when world loads - restore from saved data."
	[world saved-data]
	(if saved-data
		(if-let [wi-data (get-saved-data-world-data saved-data)]
			(do
				(wd/register-world-data! world wi-data)
				(log/info "Restored WiWorldData for world from save")
				wi-data)
			(let [fresh (wd/create-world-data world)]
				(wd/register-world-data! world fresh)
				fresh))
		(let [fresh (wd/create-world-data world)]
			(wd/register-world-data! world fresh)
			fresh)))

(defn on-world-save
	"Called before world save - prepare data for serialization."
	[world]
	(if-let [wi-data (wd/get-world-data-non-create world)]
		(do
			(wd/network-impl-validator wi-data)
			(wd/node-connection-impl-validator wi-data)
			(log/info "Prepared WiWorldData for save")
			(WiSavedDataWrapper. wi-data))
		nil))

(defn on-world-unload
	"Called when world unloads - cleanup."
	[world]
	(wd/remove-world-data! world)
	(log/info "Cleaned up WiWorldData for unloaded world"))

(defn init-world-data!
	"Register wireless world data lifecycle handlers."
	[]
	(log/info "Registering wireless world data lifecycle handlers...")
	(world-lifecycle/register-world-lifecycle-handler!
		{:on-load on-world-load
		 :on-unload on-world-unload
		 :on-save on-world-save})
	(log/info "World data system initialized"))