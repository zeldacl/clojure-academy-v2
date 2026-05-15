(ns cn.li.ac.wireless.data.world
  "World-level wireless registry and lifecycle.

  This namespace keeps all lifecycle and persistence functions explicit
  to make behavior easier to understand and debug."
  (:require [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.ac.wireless.data.spatial-lookup :as spatial-lookup]
            [cn.li.ac.wireless.data.network-lookup :as network-lookup]
            [cn.li.ac.wireless.data.world-topology :as topology]
            [cn.li.ac.wireless.data.world-runtime :as runtime]
            [cn.li.mcmod.events.world-lifecycle :as world-lifecycle]
            [cn.li.mcmod.util.log :as log]))

;; Protocol for saved data wrapper to support Clojure deftype
(defprotocol IWiSavedData
  (get-wi-data [this] "Get the WiWorldData from the wrapper"))

(defn create-world-data
  "Create new world data for a world."
  [world]
  (world-registry/create-world-data world))

(defn get-world-data
  "Get world data for a world, creating it if missing."
  [world]
  (world-registry/get-world-data world))

(defn get-world-data-non-create
  "Get world data without creating."
  [world]
  (world-registry/get-world-data-non-create world))

(defn register-world-data!
  "Register a world -> WiWorldData mapping in the registry."
  [world wi-data]
  (world-registry/register-world-data! world wi-data))

(defn remove-world-data!
  "Remove world data (called on world unload)."
  [world]
  (world-registry/remove-world-data! world))

(defn add-to-spatial-index!
  "Add a vblock to the spatial index."
  [world-data vblock]
  (spatial-lookup/add-to-spatial-index! world-data vblock))

(defn remove-from-spatial-index!
  "Remove a vblock from the spatial index."
  [world-data vblock]
  (spatial-lookup/remove-from-spatial-index! world-data vblock))

(defn get-nearby-chunks
  "Get chunk keys within range of a position (delegates to spatial-index)."
  [x y z search-radius]
  (spatial-lookup/get-nearby-chunks x y z search-radius))

(defn get-vblocks-in-chunks
  "Get all vblocks in the specified chunks."
  [world-data chunk-keys]
  (spatial-lookup/get-vblocks-in-chunks world-data chunk-keys))

(defn get-network-by-matrix
  "Get network by matrix vblock."
  [world-data matrix-vblock]
  (network-lookup/get-network-by-matrix world-data matrix-vblock))

(defn get-network-by-node
  "Get network by node vblock."
  [world-data node-vblock]
  (network-lookup/get-network-by-node world-data node-vblock))

(defn get-network-by-ssid
  "Get network by SSID string."
  [world-data ssid]
  (network-lookup/get-network-by-ssid world-data ssid))

(defn range-search-networks
  "Search for networks within range of coordinates using the spatial index."
  [world-data x y z search-radius max-results]
  (network-lookup/range-search-networks world-data x y z search-radius max-results))

(defn get-node-connection
  "Get node connection by node/generator/receiver vblock."
  [world-data vblock]
  (network-lookup/get-node-connection world-data vblock))

(def create-network-impl! topology/create-network-impl!)
(def destroy-network-impl! topology/destroy-network-impl!)
(def create-node-connection-impl! topology/create-node-connection-impl!)
(def destroy-node-connection-impl! topology/destroy-node-connection-impl!)
(def ensure-node-connection! topology/ensure-node-connection!)
(def link-node-to-network! topology/link-node-to-network!)
(def link-generator-to-node-connection! topology/link-generator-to-node-connection!)
(def link-receiver-to-node-connection! topology/link-receiver-to-node-connection!)
(def rebuild-network-indexes! topology/rebuild-network-indexes!)
(def rebuild-connection-indexes! topology/rebuild-connection-indexes!)

(def network-impl-validator runtime/network-impl-validator)
(def node-connection-impl-validator runtime/node-connection-impl-validator)
(def tick-world-data! runtime/tick-world-data!)

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

(defn create-saved-data
  "Create a saved data wrapper for a world."
  [world]
  (WiSavedDataWrapper. (create-world-data world)))

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
        (register-world-data! world wi-data)
        (log/info "Restored WiWorldData for world from save")
        wi-data)
      (let [fresh (create-world-data world)]
        (register-world-data! world fresh)
        fresh))
    (let [fresh (create-world-data world)]
      (register-world-data! world fresh)
      fresh)))

(defn on-world-save
  "Called before world save - prepare data for serialization."
  [world]
  (if-let [wi-data (get-world-data-non-create world)]
    (do
      (network-impl-validator wi-data)
      (node-connection-impl-validator wi-data)
      (log/info "Prepared WiWorldData for save")
      (WiSavedDataWrapper. wi-data))
    nil))

(defn on-world-unload
  "Called when world unloads - cleanup."
  [world]
  (remove-world-data! world)
  (log/info "Cleaned up WiWorldData for unloaded world"))

(def get-statistics runtime/get-statistics)
(def print-statistics runtime/print-statistics)

(defn init-world-data! []
  (log/info "Registering wireless world data lifecycle handlers...")
  (world-lifecycle/register-world-lifecycle-handler!
    {:on-load on-world-load
     :on-unload on-world-unload
     :on-save on-world-save})
  (log/info "World data system initialized"))
