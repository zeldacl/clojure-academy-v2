(ns cn.li.ac.wireless.domain.network
  "Wireless network domain model - pure data.
  
  Represents the logical structure of a wireless network without
  any implementation details. This is immutable data passed between
  service and persistence layers.
  
  Key design: Contains NO atoms, NO business logic, NO platform references.
  All state is passed as parameters or stored in atoms at service level."
  (:require [cn.li.ac.foundation.vblock :as vb]
            [cn.li.ac.foundation.validation :as val]
            [cn.li.ac.wireless.domain.energy :as domain-energy]))

;; ============================================================================
;; Network Data Structure
;; ============================================================================

(defrecord Network
  [id                  ; keyword - unique network identifier
   ssid                ; string - network name
   password            ; string - network password (may be empty)
   matrix-vblock       ; VBlock - matrix position
   nodes               ; vector of VBlock - connected nodes
   energy              ; map {:current double :max double}
   created-at          ; long - timestamp when created
   last-updated        ; long - timestamp of last update
   metadata])          ; map - arbitrary metadata

;; ============================================================================
;; Network Validation
;; ============================================================================

(defn valid-network?
  "Check if network is structurally valid.
  
  Args:
    network: Network record
    
  Returns:
    boolean"
  [network]
  (and (map? network)
       (keyword? (:id network))
       (val/valid-ssid? (:ssid network))
       (val/valid-password? (:password network))
       (vb/vblock? (:matrix-vblock network))
       (vector? (:nodes network))
       (every? vb/vblock? (:nodes network))
       (map? (:energy network))
       (number? (:current (:energy network)))
       (number? (:max (:energy network)))
       (> (:max (:energy network)) 0)
       (>= (:current (:energy network)) 0)
       (number? (:created-at network))
       (number? (:last-updated network))))

(defn validate-network
  "Validate network and return errors.
  
  Args:
    network: Network record
    
  Returns:
    {:valid boolean :errors [string]}"
  [network]
  (let [errors (cond-> []
                  (not (keyword? (:id network)))
                  (conj "Invalid network ID")
                  
                  (not (val/valid-ssid? (:ssid network)))
                  (conj "Invalid SSID")
                  
                  (not (val/valid-password? (:password network)))
                  (conj "Invalid password")
                  
                  (not (vb/vblock? (:matrix-vblock network)))
                  (conj "Invalid matrix position")
                  
                  (not (and (vector? (:nodes network))
                            (every? vb/vblock? (:nodes network))))
                  (conj "Invalid nodes list")
                  
                  (not (and (map? (:energy network))
                            (number? (:current (:energy network)))
                            (number? (:max (:energy network)))))
                  (conj "Invalid energy structure")
                  
                  (and (number? (:max (:energy network)))
                       (<= (:max (:energy network)) 0))
                  (conj "Energy capacity must be positive")
                  
                  (and (number? (:current (:energy network)))
                       (number? (:max (:energy network)))
                       (> (:current (:energy network)) (:max (:energy network))))
                  (conj "Current energy exceeds capacity"))]
    {:valid (empty? errors) :errors errors}))

;; ============================================================================
;; Network Construction
;; ============================================================================

(defn create-network
  "Create a new network domain object.
  
  Args:
    ssid: Network name
    password: Network password (optional)
    matrix-vblock: VBlock for matrix position
    max-energy: Maximum energy capacity
    
  Returns:
    Network record"
  [ssid password matrix-vblock max-energy]
  (let [now (System/currentTimeMillis)]
    (->Network
      (keyword (str "net-" (java.util.UUID/randomUUID)))
      ssid
      (or password "")
      matrix-vblock
      []
      {:current 0.0 :max (double max-energy)}
      now
      now
      {})))

;; ============================================================================
;; Network Operations (Pure)
;; ============================================================================

(defn add-node
  "Add a node to the network (returns new network, does not mutate).
  
  Args:
    network: Network record
    node-vblock: VBlock to add
    
  Returns:
    New Network record with node added"
  [network node-vblock]
  (if (some #(= (:x %) (:x node-vblock)
                (:y %) (:y node-vblock)
                (:z %) (:z node-vblock))
            (:nodes network))
    network  ;; Node already exists
    (assoc network
           :nodes (conj (:nodes network) node-vblock)
           :last-updated (System/currentTimeMillis))))

(defn remove-node
  "Remove a node from the network.
  
  Args:
    network: Network record
    node-vblock: VBlock to remove
    
  Returns:
    New Network record with node removed"
  [network node-vblock]
  (assoc network
         :nodes (filterv #(not (= (:x %) (:x node-vblock)
                                  (:y %) (:y node-vblock)
                                  (:z %) (:z node-vblock)))
                         (:nodes network))
         :last-updated (System/currentTimeMillis)))

(defn set-energy
  "Update network energy (returns new network).
  
  Args:
    network: Network record
    current: Current energy
    
  Returns:
    New Network record with energy updated"
  [network current]
  (let [max-energy (get-in network [:energy :max])
        clamped (val/clamp-energy current max-energy)]
    (assoc-in network [:energy :current] clamped)))

(defn update-metadata
  "Update network metadata.
  
  Args:
    network: Network record
    updates: Map of metadata updates
    
  Returns:
    New Network record with metadata updated"
  [network updates]
  (assoc network :metadata (merge (:metadata network) updates)))

;; ============================================================================
;; Network Queries
;; ============================================================================

(defn get-node-count
  "Get number of nodes in network.
  
  Args:
    network: Network record
    
  Returns:
    int"
  [network]
  (count (:nodes network)))

(defn contains-node?
  "Check if network contains a node.
  
  Args:
    network: Network record
    node-vblock: VBlock to check
    
  Returns:
    boolean"
  [network node-vblock]
  (some #(= (:x %) (:x node-vblock)
            (:y %) (:y node-vblock)
            (:z %) (:z node-vblock))
        (:nodes network)))

(defn get-energy
  "Get current energy amount.
  
  Args:
    network: Network record
    
  Returns:
    double"
  [network]
  (get-in network [:energy :current]))

(defn get-max-energy
  "Get maximum energy capacity.
  
  Args:
    network: Network record
    
  Returns:
    double"
  [network]
  (get-in network [:energy :max]))

(defn get-energy-percent
  "Get energy as percentage of capacity.
  
  Args:
    network: Network record
    
  Returns:
    double (0.0 - 1.0)"
  [network]
  (domain-energy/get-energy-percent
    {:current (get-energy network)
     :max-capacity (get-max-energy network)}))

(defn network->map
  "Convert network to plain map for serialization.
  
  Args:
    network: Network record
    
  Returns:
    Plain map representation"
  [network]
  {:id (:id network)
   :ssid (:ssid network)
   :password (:password network)
   :matrix-vblock (vb/vblock->map (:matrix-vblock network))
   :nodes (mapv vb/vblock->map (:nodes network))
   :energy (:energy network)
   :created-at (:created-at network)
   :last-updated (:last-updated network)
   :metadata (:metadata network)})

(defn map->network
  "Reconstruct network from map.
  
  Args:
    m: Plain map representation
    
  Returns:
    Network record"
  [m]
  (->Network
    (:id m)
    (:ssid m)
    (:password m)
    (vb/map->vblock (:matrix-vblock m))
    (mapv vb/map->vblock (:nodes m))
    (:energy m)
    (:created-at m)
    (:last-updated m)
    (:metadata m)))

;; ============================================================================
;; Network Status
;; ============================================================================

(defn is-active?
  "Check if network is active (has nodes).
  
  Args:
    network: Network record
    
  Returns:
    boolean"
  [network]
  (> (get-node-count network) 0))

(defn is-full?
  "Check if network is at capacity.
  
  Args:
    network: Network record
    max-nodes: Maximum allowed nodes
    
  Returns:
    boolean"
  [network max-nodes]
  (>= (get-node-count network) max-nodes))

(defn network-summary
  "Get human-readable network summary.
  
  Args:
    network: Network record
    
  Returns:
    String description"
  [network]
  (format "Network '%s' (ID: %s) with %d nodes, energy: %.1f/%.1f"
          (:ssid network)
          (name (:id network))
          (get-node-count network)
          (get-energy network)
          (get-max-energy network)))