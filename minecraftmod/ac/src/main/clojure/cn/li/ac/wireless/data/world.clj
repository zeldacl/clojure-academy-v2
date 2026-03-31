(ns cn.li.ac.wireless.data.world
  "World-level wireless registry and lifecycle.

  This namespace keeps all lifecycle and persistence functions explicit
  to make behavior easier to understand and debug."
  (:require [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network :as network]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.mcmod.events.world-lifecycle :as world-lifecycle]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.util.log :as log]))

(defrecord WiWorldData
  [world
   net-lookup
   node-lookup
   spatial-index
   networks
   connections])

(def ^:private world-data-registry (atom {}))

(defn create-world-data
  "Create new world data for a world."
  [world]
  (->WiWorldData
    world
    (atom {})
    (atom {})
    (atom {})
    (atom [])
    (atom [])))

(defn get-world-data
  "Get world data for a world, creating it if missing."
  [world]
  (or (get @world-data-registry world)
      (let [created (create-world-data world)]
        (swap! world-data-registry #(if (contains? % world) % (assoc % world created)))
        (get @world-data-registry world))))

(defn get-world-data-non-create
  "Get world data without creating."
  [world]
  (get @world-data-registry world))

(defn remove-world-data!
  "Remove world data (called on world unload)."
  [world]
  (swap! world-data-registry dissoc world)
  (log/info (format "Removed WiWorldData for world: %s" world)))

(defn- pos->chunk-key
  "Convert world position to chunk key [cx cy cz]."
  [x y z]
  [(quot x 16) (quot y 16) (quot z 16)])

(defn add-to-spatial-index!
  "Add a vblock to the spatial index."
  [world-data vblock]
  (let [chunk-key (pos->chunk-key (:x vblock) (:y vblock) (:z vblock))]
    (swap! (:spatial-index world-data)
           update chunk-key (fnil conj #{}) vblock)))

(defn remove-from-spatial-index!
  "Remove a vblock from the spatial index."
  [world-data vblock]
  (let [chunk-key (pos->chunk-key (:x vblock) (:y vblock) (:z vblock))]
    (swap! (:spatial-index world-data)
           (fn [idx]
             (if-let [chunk-set (get idx chunk-key)]
               (let [new-set (disj chunk-set vblock)]
                 (if (empty? new-set)
                   (dissoc idx chunk-key)
                   (assoc idx chunk-key new-set)))
               idx)))))

(defn get-nearby-chunks
  "Get chunk keys within range of a position."
  [x y z range]
  (let [chunk-range (inc (quot range 16))
        cx (quot x 16)
        cy (quot y 16)
        cz (quot z 16)]
    (for [dx (range (- chunk-range) (inc chunk-range))
          dy (range (- chunk-range) (inc chunk-range))
          dz (range (- chunk-range) (inc chunk-range))]
      [(+ cx dx) (+ cy dy) (+ cz dz)])))

(defn get-vblocks-in-chunks
  "Get all vblocks in the specified chunks."
  [world-data chunk-keys]
  (let [idx @(:spatial-index world-data)]
    (reduce (fn [acc chunk-key]
              (if-let [vblocks (get idx chunk-key)]
                (into acc vblocks)
                acc))
            #{}
            chunk-keys)))

(defn get-network-by-matrix
  "Get network by matrix vblock."
  [world-data matrix-vblock]
  (get @(:net-lookup world-data) matrix-vblock))

(defn get-network-by-node
  "Get network by node vblock."
  [world-data node-vblock]
  (get @(:net-lookup world-data) node-vblock))

(defn get-network-by-ssid
  "Get network by SSID string."
  [world-data ssid]
  (get @(:net-lookup world-data) ssid))

(defn range-search-networks
  "Search for networks within range of coordinates using the spatial index."
  [world-data x y z range max-results]
  (let [range-sq (* range range)
        chunk-keys (get-nearby-chunks x y z range)
        candidate-vblocks (get-vblocks-in-chunks world-data chunk-keys)
        net-lookup @(:net-lookup world-data)]
    (->> candidate-vblocks
         (keep (fn [vblock]
                 (when-let [net (get net-lookup vblock)]
                   (when (= vblock (:matrix net))
                     (when (<= (vb/dist-sq-pos vblock x y z) range-sq)
                       net)))))
         (distinct)
         (take max-results))))

(defn get-node-connection
  "Get node connection by node/generator/receiver vblock."
  [world-data vblock]
  (get @(:node-lookup world-data) vblock))

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
      (swap! (:networks world-data) conj item)
      (swap! (:net-lookup world-data) assoc matrix-vblock item ssid item)
      (add-to-spatial-index! world-data matrix-vblock)
      (log/info (format "Created network: SSID='%s'" ssid))
      true)))

(defn destroy-network-impl!
  "Destroy network and clear all related lookups/indexes."
  [world-data item]
  (reset! (:disposed item) true)
  (swap! (:net-lookup world-data) dissoc (:matrix item) @(:ssid item))
  (doseq [node @(:nodes item)]
    (swap! (:net-lookup world-data) dissoc node)
    (remove-from-spatial-index! world-data node))
  (remove-from-spatial-index! world-data (:matrix item))
  (swap! (:networks world-data) (fn [items] (filterv #(not= % item) items)))
  (log/info (format "Destroyed network: SSID='%s'" @(:ssid item))))

(defn create-node-connection-impl!
  "Create node connection and register lookups/indexes.
  Returns created connection, or false if it already exists."
  [world-data node-vblock]
  (if (get-node-connection world-data node-vblock)
    false
    (let [item (node-conn/create-node-conn world-data node-vblock)]
      (swap! (:connections world-data) conj item)
      (swap! (:node-lookup world-data) assoc node-vblock item)
      (add-to-spatial-index! world-data node-vblock)
      item)))

(defn destroy-node-connection-impl!
  "Destroy node connection and clear all related lookups/indexes."
  [world-data item]
  (reset! (:disposed item) true)
  (swap! (:node-lookup world-data) dissoc (:node item))
  (doseq [device @(:generators item)]
    (swap! (:node-lookup world-data) dissoc device))
  (doseq [device @(:receivers item)]
    (swap! (:node-lookup world-data) dissoc device))
  (remove-from-spatial-index! world-data (:node item))
  (swap! (:connections world-data) (fn [items] (filterv #(not= % item) items)))
  (log/info (format "Destroyed node connection: %s" (vb/vblock-to-string (:node item)))))

(defn ensure-node-connection!
  "Get or create node connection for a node."
  [world-data node-vblock]
  (or (get-node-connection world-data node-vblock)
      (create-node-connection-impl! world-data node-vblock)))

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
      (swap! (:net-lookup world-data) assoc (:matrix net) net @(:ssid net) net)
      (add-to-spatial-index! world-data (:matrix net))
      (doseq [node @(:nodes net)]
        (swap! (:net-lookup world-data) assoc node net)
        (add-to-spatial-index! world-data node)))

    (doseq [conn connections]
      (swap! (:node-lookup world-data) assoc (:node conn) conn)
      (add-to-spatial-index! world-data (:node conn))
      (doseq [generator @(:generators conn)]
        (swap! (:node-lookup world-data) assoc generator conn))
      (doseq [receiver @(:receivers conn)]
        (swap! (:node-lookup world-data) assoc receiver conn)))

    world-data))

(deftype WiSavedDataWrapper
  [^:volatile-mutable wi-data]
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
      (.-wi-data saved-data)
      (catch Exception _ nil))))

(defn on-world-load
  "Called when world loads - restore from saved data."
  [world saved-data]
  (if saved-data
    (if-let [wi-data (get-saved-data-world-data saved-data)]
      (do
        (swap! world-data-registry assoc world wi-data)
        (log/info "Restored WiWorldData for world from save")
        wi-data)
      (let [fresh (create-world-data world)]
        (swap! world-data-registry assoc world fresh)
        fresh))
    (let [fresh (create-world-data world)]
      (swap! world-data-registry assoc world fresh)
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
