(ns my-mod.wireless.interfaces
  "Wireless system interfaces - Clojure protocols matching Java interfaces
  
  These protocols define the contracts for wireless energy system components:
  - IWirelessTile: Base marker interface
  - IWirelessUser: Base for generators/receivers
  - IWirelessMatrix: Network matrix (center node)
  - IWirelessNode: Wireless node
  - IWirelessGenerator: Energy generator
  - IWirelessReceiver: Energy receiver
  
  All interfaces are implemented using Clojure protocols for maximum flexibility."
  (:require [my-mod.util.log :as log]))

;; ============================================================================
;; Base Interfaces
;; ============================================================================

(defprotocol IWirelessTile
  "Empty marker interface indicating this is a wireless tile.
  Don't use this directly - use specific interfaces instead.")

(defprotocol IWirelessUser
  "Base interface for wireless energy users (generators and receivers).
  Extends IWirelessTile.")

;; ============================================================================
;; IWirelessMatrix - Network Matrix (Center Node)
;; ============================================================================

(defprotocol IWirelessMatrix
  "Information providing interface of a wireless matrix.
  The matrix is the center of a wireless network (SSID-based).
  
  Extends: IWirelessTile"
  
  (get-matrix-capacity [this]
    "How many nodes this matrix can hold.
    Returns: int - node capacity")
  
  (get-matrix-bandwidth [this]
    "How much energy allowed to balance between nodes each tick.
    Returns: double - energy per tick")
  
  (get-matrix-range [this]
    "The max range that this matrix can reach.
    Returns: double - range in blocks"))

;; ============================================================================
;; IWirelessNode - Wireless Node
;; ============================================================================

(defprotocol IWirelessNode
  "Information providing interface of a wireless node.
  Nodes store energy and can connect to networks and users.
  
  Extends: IWirelessTile"
  
  (get-max-energy [this]
    "Maximum energy capacity of this node.
    Returns: double - max energy")
  
  (get-energy [this]
    "Current energy stored in this node.
    Returns: double - current energy")
  
  (set-energy [this value]
    "Set the energy level of this node.
    Parameters:
    - value: double - new energy value
    Returns: void")
  
  (get-bandwidth [this]
    "How much energy this node can transfer each tick.
    Returns: double - bandwidth (energy per tick)")
  
  (get-capacity [this]
    "How many generators/receivers this node can connect to.
    Returns: int - connection capacity")
  
  (get-range [this]
    "How far this node's signal can reach.
    Returns: double - range in blocks")
  
  (get-node-name [this]
    "The user custom name of the node.
    Returns: String - node name")
  
  (get-password [this]
    "The password of the node.
    Returns: String - password"))

;; ============================================================================
;; IWirelessGenerator - Energy Generator
;; ============================================================================

(defprotocol IWirelessGenerator
  "Wireless energy generator interface.
  Generators provide energy to connected nodes.
  
  Extends: IWirelessUser"
  
  (get-provided-energy [this req]
    "Get how much energy this generator can provide.
    Parameters:
    - req: double - how much energy is required
    Returns: double - provided energy (0 <= ret <= req)")
  
  (get-generator-bandwidth [this]
    "Max energy transmitted each tick.
    Returns: double - bandwidth"))

;; ============================================================================
;; IWirelessReceiver - Energy Receiver
;; ============================================================================

(defprotocol IWirelessReceiver
  "Wireless energy receiver interface.
  Receivers consume energy from connected nodes.
  
  Extends: IWirelessUser"
  
  (get-required-energy [this]
    "How much energy this receiver needs.
    Returns: double - required energy")
  
  (inject-energy [this amt]
    "Inject some amount of energy into the machine.
    Amount is ALWAYS positive.
    Parameters:
    - amt: double - energy to inject
    Returns: double - energy not injected (leftover)")
  
  (pull-energy [this amt]
    "Pull some energy out of the machine.
    Amount is ALWAYS positive.
    Parameters:
    - amt: double - energy to pull
    Returns: double - energy really pulled out")
  
  (get-receiver-bandwidth [this]
    "How much energy this receiver can retrieve each tick.
    Returns: double - bandwidth"))

;; ============================================================================
;; Helper Functions for Type Checking
;; ============================================================================

(defn wireless-tile? [obj]
  "Check if object satisfies IWirelessTile protocol"
  (satisfies? IWirelessTile obj))

(defn wireless-user? [obj]
  "Check if object satisfies IWirelessUser protocol"
  (satisfies? IWirelessUser obj))

(defn wireless-matrix? [obj]
  "Check if object satisfies IWirelessMatrix protocol"
  (satisfies? IWirelessMatrix obj))

(defn wireless-node? [obj]
  "Check if object satisfies IWirelessNode protocol"
  (satisfies? IWirelessNode obj))

(defn wireless-generator? [obj]
  "Check if object satisfies IWirelessGenerator protocol"
  (satisfies? IWirelessGenerator obj))

(defn wireless-receiver? [obj]
  "Check if object satisfies IWirelessReceiver protocol"
  (satisfies? IWirelessReceiver obj))

;; ============================================================================
;; Validation Helpers
;; ============================================================================

(defn validate-energy-value
  "Validate energy value is non-negative"
  [value]
  (when (< value 0.0)
    (throw (IllegalArgumentException. "Energy value cannot be negative")))
  value)

(defn validate-bandwidth
  "Validate bandwidth is positive"
  [bandwidth]
  (when (<= bandwidth 0.0)
    (throw (IllegalArgumentException. "Bandwidth must be positive")))
  bandwidth)

(defn validate-range
  "Validate range is positive"
  [range]
  (when (<= range 0.0)
    (throw (IllegalArgumentException. "Range must be positive")))
  range)

(defn validate-capacity
  "Validate capacity is positive integer"
  [capacity]
  (when (<= capacity 0)
    (throw (IllegalArgumentException. "Capacity must be positive")))
  capacity)

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-wireless-interfaces! []
  (log/info "Wireless interfaces initialized"))
