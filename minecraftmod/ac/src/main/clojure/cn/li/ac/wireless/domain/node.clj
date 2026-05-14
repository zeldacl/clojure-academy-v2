(ns cn.li.ac.wireless.domain.node
  "Wireless node domain model - pure data.
  
  Represents a wireless node (receiver/transmitter) in the network.
  Pure data structure with no implementation."
  (:require [cn.li.ac.foundation.vblock :as vb]
            [cn.li.ac.foundation.validation :as val]))

;; ============================================================================
;; Node Data Structure
;; ============================================================================

(defrecord WirelessNode
  [id                  ; keyword - unique node identifier
   vblock              ; VBlock - physical location
   node-type           ; keyword - :transmitter, :receiver, :relay
   network-id          ; keyword - ID of network this node belongs to (or nil)
   signal-strength     ; int - 0-100 signal quality
   connected-since     ; long - timestamp when connected
   metadata])          ; map - arbitrary metadata

;; ============================================================================
;; Node Validation
;; ============================================================================

(defn valid-node-type?
  "Check if node type is valid.
  
  Args:
    node-type: keyword
    
  Returns:
    boolean"
  [node-type]
  (contains? #{:transmitter :receiver :relay :matrix} node-type))

(defn valid-node?
  "Check if node is structurally valid.
  
  Args:
    node: WirelessNode record
    
  Returns:
    boolean"
  [node]
  (and (map? node)
       (keyword? (:id node))
       (vb/vblock? (:vblock node))
       (valid-node-type? (:node-type node))
       (or (keyword? (:network-id node)) (nil? (:network-id node)))
       (integer? (:signal-strength node))
       (>= (:signal-strength node) 0)
       (<= (:signal-strength node) 100)
       (or (number? (:connected-since node)) (nil? (:connected-since node)))
       (map? (:metadata node))))

;; ============================================================================
;; Node Construction
;; ============================================================================

(defn create-node
  "Create a new wireless node.
  
  Args:
    vblock: VBlock position
    node-type: keyword (:transmitter, :receiver, :relay, :matrix)
    
  Returns:
    WirelessNode record"
  [vblock node-type]
  (->WirelessNode
    (keyword (str "node-" (java.util.UUID/randomUUID)))
    vblock
    node-type
    nil
    100
    nil
    {}))

;; ============================================================================
;; Node Operations (Pure)
;; ============================================================================

(defn connect-to-network
  "Connect node to a network.
  
  Args:
    node: WirelessNode record
    network-id: Network ID
    signal-strength: 0-100
    
  Returns:
    New WirelessNode record"
  [node network-id signal-strength]
  (assoc node
         :network-id network-id
         :signal-strength (-> signal-strength (max 0) (min 100))
         :connected-since (System/currentTimeMillis)))

(defn disconnect-from-network
  "Disconnect node from network.
  
  Args:
    node: WirelessNode record
    
  Returns:
    New WirelessNode record"
  [node]
  (assoc node
         :network-id nil
         :signal-strength 0))

(defn update-signal-strength
  "Update signal strength for node.
  
  Args:
    node: WirelessNode record
    strength: 0-100
    
  Returns:
    New WirelessNode record"
  [node strength]
  (assoc node :signal-strength (-> strength (max 0) (min 100))))

(defn update-metadata
  "Update node metadata.
  
  Args:
    node: WirelessNode record
    updates: Map of metadata updates
    
  Returns:
    New WirelessNode record"
  [node updates]
  (assoc node :metadata (merge (:metadata node) updates)))

;; ============================================================================
;; Node Queries
;; ============================================================================

(defn is-connected?
  "Check if node is connected to a network.
  
  Args:
    node: WirelessNode record
    
  Returns:
    boolean"
  [node]
  (some? (:network-id node)))

(defn get-connection-duration
  "Get how long node has been connected (milliseconds).
  
  Args:
    node: WirelessNode record
    
  Returns:
    long or nil"
  [node]
  (when (:connected-since node)
    (- (System/currentTimeMillis) (:connected-since node))))

(defn node->map
  "Convert node to plain map for serialization.
  
  Args:
    node: WirelessNode record
    
  Returns:
    Plain map"
  [node]
  {:id (:id node)
   :vblock (vb/vblock->map (:vblock node))
   :node-type (:node-type node)
   :network-id (:network-id node)
   :signal-strength (:signal-strength node)
   :connected-since (:connected-since node)
   :metadata (:metadata node)})

(defn map->node
  "Reconstruct node from map.
  
  Args:
    m: Plain map
    
  Returns:
    WirelessNode record"
  [m]
  (->WirelessNode
    (:id m)
    (vb/map->vblock (:vblock m))
    (:node-type m)
    (:network-id m)
    (:signal-strength m)
    (:connected-since m)
    (:metadata m)))

;; ============================================================================
;; Node Status
;; ============================================================================

(defn node-summary
  "Get human-readable node summary.
  
  Args:
    node: WirelessNode record
    
  Returns:
    String description"
  [node]
  (let [status (if (is-connected? node) "connected" "disconnected")
        pos (:vblock node)]
    (format "Node %s (%s) at [%d, %d, %d] - signal: %d%%"
            (name (:id node))
            status
            (:x pos) (:y pos) (:z pos)
            (:signal-strength node))))

(defn is-transmitter?
  "Check if node is a transmitter.
  
  Args:
    node: WirelessNode record
    
  Returns:
    boolean"
  [node]
  (= :transmitter (:node-type node)))

(defn is-receiver?
  "Check if node is a receiver.
  
  Args:
    node: WirelessNode record
    
  Returns:
    boolean"
  [node]
  (= :receiver (:node-type node)))

(defn is-relay?
  "Check if node is a relay.
  
  Args:
    node: WirelessNode record
    
  Returns:
    boolean"
  [node]
  (= :relay (:node-type node)))

(defn is-matrix?
  "Check if node is a matrix.
  
  Args:
    node: WirelessNode record
    
  Returns:
    boolean"
  [node]
  (= :matrix (:node-type node)))