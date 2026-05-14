(ns cn.li.ac.wireless.service.network-manager
  "Network lifecycle management service.
  
  Responsibilities:
  - Create/dispose networks
  - Register/unregister nodes
  - Network state transitions
  - Lifecycle hooks"
  (:require [cn.li.ac.wireless.domain.network :as domain-net]
            [cn.li.ac.wireless.domain.node :as domain-node]
            [cn.li.ac.foundation.concurrency :as conc]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Network Registry
;; ============================================================================

(defn create-network-registry
  "Create a registry for all networks.
  
  Returns:
    atom containing {network-id network}"
  []
  (atom {}))

;; ============================================================================
;; Network Lifecycle
;; ============================================================================

(defn create-network!
  "Create a new network and register it.
  
  Args:
    registry: Network registry atom
    ssid: Network name
    password: Network password
    matrix-vblock: VBlock for matrix
    max-energy: Maximum energy capacity
    
  Returns:
    Created network or nil if SSID exists"
  [registry ssid password matrix-vblock max-energy]
  (let [new-net (domain-net/create-network ssid password matrix-vblock max-energy)]
    (when-not (some #(= ssid (:ssid %)) (vals @registry))
      (swap! registry assoc (:id new-net) new-net)
      new-net)))

(defn get-network
  "Retrieve network by ID.
  
  Args:
    registry: Network registry atom
    network-id: keyword
    
  Returns:
    Network or nil"
  [registry network-id]
  (get @registry network-id))

(defn get-networks-by-ssid
  "Get all networks with given SSID.
  
  Args:
    registry: Network registry atom
    ssid: Network name
    
  Returns:
    Vector of networks"
  [registry ssid]
  (filterv #(= ssid (:ssid %)) (vals @registry)))

(defn dispose-network!
  "Dispose a network and unregister it.
  
  Args:
    registry: Network registry atom
    network-id: Network to dispose
    
  Returns:
    Disposed network or nil"
  [registry network-id]
  (let [network (get @registry network-id)]
    (when network
      (swap! registry dissoc network-id)
      (log/info (str "Network " network-id " disposed")))
    network))

;; ============================================================================
;; Node Management
;; ============================================================================

(defn add-node-to-network!
  "Add a node to a network.
  
  Args:
    registry: Network registry atom
    network-id: Network ID
    node-vblock: VBlock for node
    
  Returns:
    {:success boolean :network network :reason string}"
  [registry network-id node-vblock]
  (if-let [network (get @registry network-id)]
    (if (domain-net/contains-node? network node-vblock)
      {:success false :reason "Node already connected"}
      (do
        (swap! registry
               (fn [reg]
                 (assoc reg network-id (domain-net/add-node network node-vblock))))
        {:success true :network (get @registry network-id)}))
    {:success false :reason "Network not found"}))

(defn remove-node-from-network!
  "Remove a node from a network.
  
  Args:
    registry: Network registry atom
    network-id: Network ID
    node-vblock: VBlock for node
    
  Returns:
    {:success boolean :network network}"
  [registry network-id node-vblock]
  (if-let [network (get @registry network-id)]
    (do
      (swap! registry
             (fn [reg]
               (assoc reg network-id (domain-net/remove-node network node-vblock))))
      {:success true :network (get @registry network-id)})
    {:success false}))

;; ============================================================================
;; Energy Management
;; ============================================================================

(defn set-network-energy!
  "Update network energy.
  
  Args:
    registry: Network registry atom
    network-id: Network ID
    energy-amount: New energy amount
    
  Returns:
    New network or nil"
  [registry network-id energy-amount]
  (when-let [network (get @registry network-id)]
    (let [updated (domain-net/set-energy network energy-amount)]
      (swap! registry assoc network-id updated)
      updated)))

(defn transfer-network-energy!
  "Transfer energy between networks.
  
  Args:
    registry: Network registry atom
    from-id: Source network ID
    to-id: Destination network ID
    amount: Amount to transfer
    efficiency: 0.0-1.0 transfer efficiency
    
  Returns:
    {:from new-net :to new-net :transferred amount}"
  [registry from-id to-id amount efficiency]
  (if-let [from-net (get @registry from-id)]
    (if-let [to-net (get @registry to-id)]
      (let [current-from (domain-net/get-energy from-net)
            extractable (min current-from amount)
            effective-amount (* extractable efficiency)
            max-to (domain-net/get-max-energy to-net)
            current-to (domain-net/get-energy to-net)
            available-space (- max-to current-to)
            receivable (min effective-amount available-space)
            
            new-from (domain-net/set-energy from-net (- current-from extractable))
            new-to (domain-net/set-energy to-net (+ current-to receivable))]
        (swap! registry assoc from-id new-from to-id new-to)
        {:from new-from :to new-to :transferred receivable})
      nil)
    nil))

;; ============================================================================
;; Registry Queries
;; ============================================================================

(defn get-all-networks
  "Get all networks.
  
  Args:
    registry: Network registry atom
    
  Returns:
    Vector of all networks"
  [registry]
  (vec (vals @registry)))

(defn get-network-count
  "Get number of networks.
  
  Args:
    registry: Network registry atom
    
  Returns:
    int"
  [registry]
  (count @registry))

(defn get-total-nodes
  "Get total number of nodes across all networks.
  
  Args:
    registry: Network registry atom
    
  Returns:
    int"
  [registry]
  (reduce + 0
          (map domain-net/get-node-count (vals @registry))))

(defn get-networks-near-position
  "Get networks near a position (rough check based on matrix block).
  
  Args:
    registry: Network registry atom
    x, y, z: Center position
    range: Search range
    
  Returns:
    Vector of nearby networks"
  [registry x y z range]
  (let [range-sq (* range range)]
    (filterv (fn [net]
               (let [matrix-vb (:matrix-vblock net)
                     dx (- (:x matrix-vb) x)
                     dy (- (:y matrix-vb) y)
                     dz (- (:z matrix-vb) z)
                     dist-sq (+ (* dx dx) (* dy dy) (* dz dz))]
                 (<= dist-sq range-sq)))
            (vals @registry))))

;; ============================================================================
;; Network Status
;; ============================================================================

(defn print-network-info
  "Print detailed network information.
  
  Args:
    network: Network record
    
  Returns:
    String"
  [network]
  (let [summary (domain-net/network-summary network)
        energy-pct (* 100 (domain-net/get-energy-percent network))]
    (str summary " [Energy: " (format "%.1f%%" energy-pct) "]")))

(defn print-registry-status
  "Print status of all networks in registry.
  
  Args:
    registry: Network registry atom
    
  Returns:
    String"
  [registry]
  (let [networks (vals @registry)
        count (count networks)
        total-nodes (reduce + 0 (map domain-net/get-node-count networks))
        total-energy (reduce + 0.0 (map domain-net/get-energy networks))]
    (str count " networks, " total-nodes " total nodes, "
         (format "%.1f" total-energy) " total energy")))
