(ns cn.li.ac.wireless.data.world
  "World-level wireless registry and lifecycle.

  This namespace keeps all lifecycle and persistence functions explicit
  to make behavior easier to understand and debug."
  (:require [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.ac.wireless.data.spatial-lookup :as spatial-lookup]
            [cn.li.ac.wireless.data.network-lookup :as network-lookup]
            [cn.li.ac.wireless.data.network :as network]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.mcmod.events.world-lifecycle :as world-lifecycle]
            [cn.li.mcmod.platform.nbt :as nbt]
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

(defn- register-network!
  [world-data net]
  (swap! (:networks world-data) conj net)
  (swap! (:net-lookup world-data) assoc (:matrix net) net @(:ssid net) net)
  (add-to-spatial-index! world-data (:matrix net))
  net)

(defn- register-network-node!
  [world-data net node-vblock]
  (swap! (:net-lookup world-data) assoc node-vblock net)
  (add-to-spatial-index! world-data node-vblock))

(defn- unregister-network-node!
  [world-data node-vblock]
  (swap! (:net-lookup world-data) dissoc node-vblock)
  (remove-from-spatial-index! world-data node-vblock))

(defn- unregister-network!
  [world-data net]
  (swap! (:net-lookup world-data) dissoc (:matrix net) @(:ssid net))
  (doseq [node @(:nodes net)]
    (unregister-network-node! world-data node))
  (remove-from-spatial-index! world-data (:matrix net))
  (swap! (:networks world-data) (fn [items] (filterv #(not= % net) items))))

(defn- register-node-connection!
  [world-data conn]
  (swap! (:connections world-data) conj conn)
  (swap! (:node-lookup world-data) assoc (:node conn) conn)
  (add-to-spatial-index! world-data (:node conn))
  conn)

(defn- register-node-device!
  [world-data conn device-vblock]
  (swap! (:node-lookup world-data) assoc device-vblock conn))

(defn- unregister-node-device!
  [world-data device-vblock]
  (swap! (:node-lookup world-data) dissoc device-vblock))

(defn- unregister-node-connection!
  [world-data conn]
  (swap! (:node-lookup world-data) dissoc (:node conn))
  (doseq [device @(:generators conn)]
    (unregister-node-device! world-data device))
  (doseq [device @(:receivers conn)]
    (unregister-node-device! world-data device))
  (remove-from-spatial-index! world-data (:node conn))
  (swap! (:connections world-data) (fn [items] (filterv #(not= % conn) items))))

(defn create-network-impl!
  "Create network and register lookups/indexes.
  Returns true on success, false when uniqueness checks fail."
  [world-data matrix-vblock ssid password]
  (cond
    (get-network-by-ssid world-data ssid)
    false

    (get-network-by-matrix world-data matrix-vblock)
    false

    :else
    (let [item (network/create-wireless-net world-data matrix-vblock ssid password)]
      (register-network! world-data item)
      (log/info (format "Created network: SSID='%s'" ssid))
      true)))

(defn destroy-network-impl!
  "Destroy network and clear all related lookups/indexes."
  [world-data item]
  (reset! (:disposed item) true)
  (unregister-network! world-data item)
  (log/info (format "Destroyed network: SSID='%s'" @(:ssid item))))

(defn create-node-connection-impl!
  "Create node connection and register lookups/indexes.
  Returns created connection, or false if it already exists."
  [world-data node-vblock]
  (if (get-node-connection world-data node-vblock)
    false
    (let [item (node-conn/create-node-conn world-data node-vblock)]
      (register-node-connection! world-data item))))

(defn destroy-node-connection-impl!
  "Destroy node connection and clear all related lookups/indexes."
  [world-data item]
  (reset! (:disposed item) true)
  (unregister-node-connection! world-data item)
  (log/info (format "Destroyed node connection: %s" (vb/vblock-to-string (:node item)))))

(defn ensure-node-connection!
  "Get or create node connection for a node."
  [world-data node-vblock]
  (or (get-node-connection world-data node-vblock)
      (create-node-connection-impl! world-data node-vblock)))

(defn link-node-to-network!
  "Link a node vblock into the specified network with password check."
  [world-data net node-vblock password-attempt]
  (when-let [old-net (get-network-by-node world-data node-vblock)]
    (network/remove-node! old-net node-vblock))
  (network/add-node! net node-vblock password-attempt))

(defn link-generator-to-node-connection!
  "Link a generator vblock to a node connection."
  [world-data conn generator-vblock]
  (when-let [old-conn (get-node-connection world-data generator-vblock)]
    (node-conn/remove-generator! old-conn generator-vblock))
  (node-conn/add-generator! conn generator-vblock))

(defn link-receiver-to-node-connection!
  "Link a receiver vblock to a node connection."
  [world-data conn receiver-vblock]
  (when-let [old-conn (get-node-connection world-data receiver-vblock)]
    (node-conn/remove-receiver! old-conn receiver-vblock))
  (node-conn/add-receiver! conn receiver-vblock))

(defn rebuild-network-indexes!
  [world-data net]
  (register-network! world-data net)
  (doseq [node @(:nodes net)]
    (register-network-node! world-data net node)))

(defn rebuild-connection-indexes!
  [world-data conn]
  (register-node-connection! world-data conn)
  (doseq [generator @(:generators conn)]
    (register-node-device! world-data conn generator))
  (doseq [receiver @(:receivers conn)]
    (register-node-device! world-data conn receiver)))

(defn network-impl-validator
  "Remove disposed/invalid networks from world-data."
  [world-data]
  (doseq [item @(:networks world-data)]
    (when (or @(:disposed item)
              (and (vb/is-chunk-loaded? (:matrix item) (:world world-data))
                   (nil? (vb/vblock-get (:matrix item) (:world world-data)))))
      (destroy-network-impl! world-data item))))

(defn node-connection-impl-validator
  "Remove disposed/invalid node connections from world-data."
  [world-data]
  (doseq [item @(:connections world-data)]
    (when (or @(:disposed item)
              (and (vb/is-chunk-loaded? (:node item) (:world world-data))
                   (nil? (vb/vblock-get (:node item) (:world world-data)))))
      (destroy-node-connection-impl! world-data item))))

(defn tick-world-data!
  "Tick all world wireless items."
  [world-data]
  (doseq [item @(:networks world-data)]
    (when-not @(:disposed item)
      (network/tick-wireless-net! item)))
  (doseq [item @(:connections world-data)]
    (when-not @(:disposed item)
      (node-conn/tick-node-conn! item))))

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
    (nbt-write-list! out "networks" @(:networks world-data) network/network-to-nbt (fn [net] @(:disposed net)))
    (nbt-write-list! out "connections" @(:connections world-data) node-conn/node-connection-to-nbt (fn [conn] @(:disposed conn)))
    out))

(defn world-data-from-nbt
  "Deserialize world-data from NBT and rebuild indexes."
  [world nbt-root]
  (let [world-data (create-world-data world)
        networks (nbt-read-list nbt-root "networks" network/network-from-nbt world-data)
        connections (nbt-read-list nbt-root "connections" node-conn/node-connection-from-nbt world-data)]
    (reset! (:networks world-data) networks)
    (reset! (:connections world-data) connections)

    (doseq [net networks]
      (rebuild-network-indexes! world-data net))

    (doseq [conn connections]
      (rebuild-connection-indexes! world-data conn))

    world-data))

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

(defn get-statistics
  "Get statistics about this world's wireless system."
  [world-data]
  {:networks (count @(:networks world-data))
   :connections (count @(:connections world-data))
   :net-lookups (count @(:net-lookup world-data))
   :node-lookups (count @(:node-lookup world-data))})

(defn print-statistics
  "Print statistics to log."
  [world-data]
  (let [stats (get-statistics world-data)]
    (log/info "=== Wireless System Statistics ===")
    (log/info (format "Networks: %d" (:networks stats)))
    (log/info (format "Connections: %d" (:connections stats)))
    (log/info (format "Network lookups: %d" (:net-lookups stats)))
    (log/info (format "Node lookups: %d" (:node-lookups stats)))))

(defn init-world-data! []
  (log/info "Registering wireless world data lifecycle handlers...")
  (world-lifecycle/register-world-lifecycle-handler!
    {:on-load on-world-load
     :on-unload on-world-unload
     :on-save on-world-save})
  (log/info "World data system initialized"))
