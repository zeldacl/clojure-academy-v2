(ns cn.li.ac.energy.api.protocol
  "Energy system public protocols.
  
  Defines all energy management contracts without any implementation.
  Enables multiple implementations and easy testing."
  (:import [java.util UUID]))

;; ============================================================================
;; IEnergyManager: Core Energy Management
;; ============================================================================

(defprotocol IEnergyManager
  "Unified energy management interface.
  
  Coordinates all energy-related operations: items, nodes, networks, transfers."
  
  (get-energy
    [this id]
    "Get current energy amount.
    
    Args:
      id: Entity ID (string UUID)
      
    Returns:
      double (amount) or nil if not found")
  
  (get-capacity
    [this id]
    "Get maximum energy capacity.
    
    Args:
      id: Entity ID
      
    Returns:
      double (capacity) or nil if not found")
  
  (set-energy
    [this id amount]
    "Set energy to exact amount (clamped).
    
    Args:
      id: Entity ID
      amount: New energy amount
      
    Returns:
      {:success boolean :reason string}")
  
  (transfer-energy
    [this source dest amount]
    [this source dest amount callback]
    "Transfer energy between containers.
    
    Args:
      source: Source ID
      dest: Destination ID
      amount: Amount to transfer
      callback: Optional callback(result) after transfer
      
    Returns:
      {:transferred amount :lost amount :reason string}")
  
  (drain-energy
    [this id amount]
    "Extract energy from container.
    
    Args:
      id: Entity ID
      amount: Amount to extract
      
    Returns:
      [success amount-extracted]")
  
  (subscribe-to-changes
    [this id callback]
    "Subscribe to energy changes.
    
    Args:
      id: Entity ID
      callback: fn(old-amount new-amount) called on change
      
    Returns:
      subscription-id (for unsubscribe)")
  
  (unsubscribe-from-changes
    [this subscription-id]
    "Unsubscribe from energy changes.
    
    Args:
      subscription-id: ID returned from subscribe
      
    Returns:
      nil")
  
  (list-energy-providers
    [this]
    "List all energy-capable entities.
    
    Returns:
      [id id id ...]"))

;; ============================================================================
;; IEnergyItem: Item-Specific Energy
;; ============================================================================

(defprotocol IEnergyItem
  "Item energy storage interface.
  
  Manages energy in ItemStack (batteries, capacitors, etc.)."
  
  (get-item-energy
    [this item-stack]
    "Get energy in item.
    
    Args:
      item-stack: ItemStack
      
    Returns:
      double")
  
  (set-item-energy
    [this item-stack amount]
    "Set energy in item.
    
    Args:
      item-stack: ItemStack
      amount: New amount
      
    Returns:
      nil (modifies in place)")
  
  (get-item-capacity
    [this item-stack]
    "Get item max capacity.
    
    Returns:
      double")
  
  (get-item-bandwidth
    [this item-stack]
    "Get max transfer rate.
    
    Returns:
      double")
  
  (is-energy-item?
    [this item-stack]
    "Check if item stores energy.
    
    Returns:
      boolean")
  
  (charge-item
    [this item-stack amount]
    "Charge item energy.
    
    Args:
      item-stack: ItemStack
      amount: Amount to charge
      
    Returns:
      amount-charged")
  
  (discharge-item
    [this item-stack amount]
    "Discharge item energy.
    
    Args:
      item-stack: ItemStack
      amount: Amount to discharge
      
    Returns:
      amount-discharged"))

;; ============================================================================
;; IEnergyNode: Node Energy (Wireless Nodes)
;; ============================================================================

(defprotocol IEnergyNode
  "Wireless node energy interface.
  
  Manages energy in wireless nodes and matrix blocks."
  
  (get-node-energy
    [this node-vblock]
    "Get node energy.
    
    Returns:
      double")
  
  (set-node-energy
    [this node-vblock amount]
    "Set node energy.
    
    Returns:
      nil")
  
  (get-node-capacity
    [this node-vblock]
    "Get node max capacity.
    
    Returns:
      double")
  
  (inject-energy
    [this node-vblock amount]
    "Add energy to node.
    
    Returns:
      amount-added")
  
  (extract-node-energy
    [this node-vblock amount]
    "Remove energy from node.
    
    Returns:
      amount-extracted"))

;; ============================================================================
;; IEnergyNetwork: Network Energy Coordination
;; ============================================================================

(defprotocol IEnergyNetwork
  "Network-level energy coordination.
  
  Manages energy distribution across all nodes in a network."
  
  (get-network-energy
    [this network-id]
    "Get total energy in network.
    
    Returns:
      double")
  
  (get-network-capacity
    [this network-id]
    "Get total capacity.
    
    Returns:
      double")
  
  (set-network-energy
    [this network-id amount]
    "Set network energy to amount (distributed equally).
    
    Returns:
      nil")
  
  (distribute-energy
    [this network-id strategy]
    [this network-id strategy amount]
    "Distribute energy using strategy.
    
    Strategies:
      :equal - distribute equally to all nodes
      :proportional - distribute by capacity ratio
      :load-balanced - prioritize lower nodes
      :priority - use priority weights
    
    Returns:
      {:distributed amount :strategy strategy}")
  
  (balance-network-energy
    [this network-id]
    "Auto-balance energy across all nodes.
    
    Returns:
      {:moved amount :reason string}"))

;; ============================================================================
;; IEnergyTransfer: High-Level Transfers
;; ============================================================================

(defprotocol IEnergyTransfer
  "Complex energy transfer operations.
  
  Handles cascading transfers, batching, scheduling."
  
  (transfer-wireless
    [this source-node dest-node amount]
    "Transfer via wireless connection.
    
    Returns:
      {:transferred :lost :range :reason}")
  
  (batch-transfer
    [this transfers]
    "Execute multiple transfers atomically.
    
    Args:
      transfers: [{:source :dest :amount} ...]
      
    Returns:
      {:total-transferred :failed []}")
  
  (schedule-transfer
    [this source dest amount interval]
    "Schedule recurring transfer.
    
    Args:
      interval: Transfer every N ticks
      
    Returns:
      schedule-id")
  
  (cancel-scheduled-transfer
    [this schedule-id]
    "Cancel scheduled transfer.
    
    Returns:
      nil"))

;; ============================================================================
;; IEnergyStorage: Persistent Storage
;; ============================================================================

(defprotocol IEnergyStorage
  "Energy data persistence.
  
  Save and load energy state to/from NBT."
  
  (save-energy-to-nbt
    [this id nbt-tag]
    "Save energy data.
    
    Returns:
      updated-nbt")
  
  (load-energy-from-nbt
    [this nbt-tag]
    "Load energy data.
    
    Returns:
      {:energy amount :capacity capacity}")
  
  (backup-energy-state
    [this timestamp]
    "Create backup of all energy.
    
    Returns:
      backup-id")
  
  (restore-energy-from-backup
    [this backup-id]
    "Restore from backup.
    
    Returns:
      {:success boolean :reason string}"))

;; ============================================================================
;; IEnergyValidator: Consistency & Validation
;; ============================================================================

(defprotocol IEnergyValidator
  "Energy system validation.
  
  Check consistency and validity of energy operations."
  
  (validate-energy-amount
    [this amount]
    "Check if amount is valid.
    
    Returns:
      {:valid boolean :errors []}")
  
  (validate-transfer
    [this source dest amount]
    "Validate transfer operation.
    
    Returns:
      {:valid boolean :reason string}")
  
  (validate-network-consistency
    [this network-id]
    "Validate network energy consistency.
    
    Returns:
      {:consistent boolean :errors []}")
  
  (repair-inconsistency
    [this network-id]
    "Attempt to repair inconsistency.
    
    Returns:
      {:repaired boolean :changes}"))

;; ============================================================================
;; IEnergyAdmin: Debugging & Admin
;; ============================================================================

(defprotocol IEnergyAdmin
  "Admin operations for debugging.
  
  For testing, debugging, and emergency operations."
  
  (admin-dump-state
    [this]
    "Dump all energy state.
    
    Returns:
      state-map")
  
  (admin-set-energy-unsafe
    [this id amount reason]
    "Forcefully set energy (with logging).
    
    Args:
      reason: Why this was forced
      
    Returns:
      {:success boolean}")
  
  (admin-reset-energy-system
    [this]
    "Reset all energy to defaults.
    
    Returns:
      nil")
  
  (admin-simulate-loss
    [this network-id amount-lost reason]
    "Simulate energy loss (for testing).
    
    Returns:
      {:lost amount}"))
