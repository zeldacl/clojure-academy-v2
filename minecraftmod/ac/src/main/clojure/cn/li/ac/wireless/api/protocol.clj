(ns cn.li.ac.wireless.api.protocol
  "Public API protocol for wireless system.
  
  This protocol defines the contract that external code should use
  to interact with the wireless system. It abstracts away internal
  implementation details."
  (:require [cn.li.ac.wireless.domain.network :as domain-net]))

;; ============================================================================
;; Wireless API Protocol
;; ============================================================================

(defprotocol IWirelessAPI
  "Main API for wireless network operations.
  
  All external code should use this protocol to interact with
  the wireless system. Implementations may vary but contract
  must be upheld."
  
  ;; Network Management
  (create-network
    [this ssid password matrix-vblock max-energy]
    "Create new wireless network.
     
     Returns: Network ID or nil if failed")
  
  (get-network
    [this network-id]
    "Get network by ID.
     
     Returns: Network record or nil")
  
  (list-networks
    [this]
    "Get all networks.
     
     Returns: Vector of networks")
  
  (find-networks-by-ssid
    [this ssid]
    "Find all networks with SSID.
     
     Returns: Vector of networks")
  
  (dispose-network
    [this network-id]
    "Dispose and remove network.
     
     Returns: Disposed network or nil")
  
  ;; Node Management
  (connect-node
    [this network-id node-vblock node-type]
    "Connect node to network.
     
     Returns: {:success boolean :reason string}")
  
  (disconnect-node
    [this network-id node-vblock]
    "Disconnect node from network.
     
     Returns: {:success boolean}")
  
  (get-connected-nodes
    [this network-id]
    "Get all nodes in network.
     
     Returns: Vector of VBlocks")
  
  ;; Energy Management
  (get-network-energy
    [this network-id]
    "Get network current energy.
     
     Returns: double or nil")
  
  (get-network-max-energy
    [this network-id]
    "Get network capacity.
     
     Returns: double or nil")
  
  (set-network-energy
    [this network-id amount]
    "Set network energy.
     
     Returns: Updated network or nil")
  
  (transfer-energy
    [this from-id to-id amount efficiency]
    "Transfer energy between networks.
     
     Returns: {:success boolean :transferred amount}")
  
  ;; Queries
  (find-networks-near
    [this x y z range]
    "Find networks within range.
     
     Returns: Vector of networks")
  
  (query-network-stats
    [this network-id]
    "Get detailed network statistics.
     
     Returns: {:id :ssid :node-count :energy-current :energy-max :energy-percent :is-active}")
  
  ;; Persistence
  (save-to-world
    [this world-compound]
    "Persist all networks to world NBT.
     
     Returns: Updated world-compound")
  
  (load-from-world
    [this world-compound]
    "Load all networks from world NBT.
     
     Returns: nil")
  
  ;; Lifecycle
  (tick!
    [this ticks-elapsed]
    "Called each game tick.
     
     Returns: nil")
  
  (shutdown
    [this]
    "Perform cleanup on server shutdown.
     
     Returns: nil"))

;; ============================================================================
;; Query API
;; ============================================================================

(defprotocol IWirelessQuery
  "Query API for wireless networks.
  
  Provides read-only access to network information."
  
  (query-network
    [this network-id]
    "Get network by ID. Returns network or nil.")
  
  (query-networks-all
    [this]
    "Get all networks. Returns vector of networks.")
  
  (query-networks-by-ssid
    [this ssid]
    "Get networks with SSID. Returns vector of networks.")
  
  (query-networks-by-position
    [this x y z range]
    "Get networks within range. Returns vector of networks.")
  
  (query-network-nodes
    [this network-id]
    "Get nodes in network. Returns vector of VBlocks.")
  
  (query-network-energy
    [this network-id]
    "Get network energy status. Returns {:current :max :percent}.")
  
  (query-statistics
    [this]
    "Get system statistics. Returns {:network-count :total-nodes :total-energy}."))

;; ============================================================================
;; Admin API (for testing/debugging)
;; ============================================================================

(defprotocol IWirelessAdmin
  "Administrative API for testing and debugging.
  
  These operations should only be accessible in dev/test environments."
  
  (admin-reset!
    [this]
    "Clear all networks. Returns nil")
  
  (admin-dump-state
    [this]
    "Get internal state for inspection. Returns state map.")
  
  (admin-validate-consistency
    [this]
    "Check internal consistency. Returns {:valid boolean :errors [string]}.")
  
  (admin-repair
    [this]
    "Attempt to repair inconsistent state. Returns {:repaired int}."))
