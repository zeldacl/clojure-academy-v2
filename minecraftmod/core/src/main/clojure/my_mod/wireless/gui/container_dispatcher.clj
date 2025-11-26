(ns my-mod.wireless.gui.container-dispatcher
  "Platform-agnostic container operation dispatcher
  
  This namespace provides unified interfaces for container operations,
  eliminating the need for platform-specific instanceof checks and cond branching.
  
  Uses protocols to achieve polymorphic dispatch based on container type."
  (:require [my-mod.wireless.gui.node-container :as node-container]
            [my-mod.wireless.gui.matrix-container :as matrix-container]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Container Operation Protocol
;; ============================================================================

(defprotocol IContainerOperations
  "Protocol for container lifecycle operations"
  
  (tick-container! [container]
    "Tick the container (called every frame on server)")
  
  (validate-container [container player]
    "Check if player can still use this container")
  
  (sync-container! [container]
    "Synchronize container data to client"))

;; ============================================================================
;; Protocol Implementations
;; ============================================================================

(extend-protocol IContainerOperations
  ;; NodeContainer implementation
  my_mod.wireless.gui.node_container.NodeContainer
  (tick-container! [container]
    (node-container/tick! container))
  
  (validate-container [container player]
    (node-container/still-valid? container player))
  
  (sync-container! [container]
    (node-container/sync-to-client! container))
  
  ;; MatrixContainer implementation
  my_mod.wireless.gui.matrix_container.MatrixContainer
  (tick-container! [container]
    (matrix-container/tick! container))
  
  (validate-container [container player]
    (matrix-container/still-valid? container player))
  
  (sync-container! [container]
    (matrix-container/sync-to-client! container)))

;; ============================================================================
;; Container Type Queries
;; ============================================================================

(defn get-container-type
  "Get the type identifier for a container
  
  Args:
  - container: NodeContainer or MatrixContainer
  
  Returns: :node or :matrix"
  [container]
  (cond
    (instance? my_mod.wireless.gui.node_container.NodeContainer container)
    :node
    
    (instance? my_mod.wireless.gui.matrix_container.MatrixContainer container)
    :matrix
    
    :else
    (do
      (log/warn "Unknown container type:" (type container))
      :unknown)))

(defn node-container?
  "Check if container is a NodeContainer"
  [container]
  (instance? my_mod.wireless.gui.node_container.NodeContainer container))

(defn matrix-container?
  "Check if container is a MatrixContainer"
  [container]
  (instance? my_mod.wireless.gui.matrix_container.MatrixContainer container))

;; ============================================================================
;; Safe Operation Wrappers
;; ============================================================================

(defn safe-tick!
  "Safely tick container with error handling
  
  Args:
  - container: Any container implementing IContainerOperations
  
  Returns: true if successful, false if error"
  [container]
  (try
    (tick-container! container)
    true
    (catch Exception e
      (log/error "Error ticking container:" (.getMessage e))
      false)))

(defn safe-validate
  "Safely validate container with error handling
  
  Args:
  - container: Any container
  - player: Player entity
  
  Returns: boolean (default false on error)"
  [container player]
  (try
    (validate-container container player)
    (catch Exception e
      (log/error "Error validating container:" (.getMessage e))
      false)))

(defn safe-sync!
  "Safely sync container with error handling
  
  Args:
  - container: Any container implementing IContainerOperations
  
  Returns: true if successful, false if error"
  [container]
  (try
    (sync-container! container)
    true
    (catch Exception e
      (log/error "Error syncing container:" (.getMessage e))
      false)))

;; ============================================================================
;; Design Notes
;; ============================================================================

;; This dispatcher provides:
;;
;; 1. **Protocol-Based Dispatch**:
;;    - Uses Clojure protocols for polymorphic method dispatch
;;    - No instanceof checks or cond branching needed
;;    - Extensible: new container types just extend protocol
;;
;; 2. **Unified Interface**:
;;    - tick-container! - Lifecycle tick
;;    - validate-container - Permission check
;;    - sync-container! - Data synchronization
;;
;; 3. **Type Queries**:
;;    - get-container-type: :node/:matrix/:unknown
;;    - node-container?, matrix-container?: predicates
;;
;; 4. **Error Handling**:
;;    - safe-* wrappers catch exceptions
;;    - Return sensible defaults on error
;;    - Log errors for debugging
;;
;; Benefits:
;; - Eliminates repetitive (instance? + cond) code
;; - Single dispatch point for all platforms
;; - Easy to add new container types
;; - Clean separation of concerns
;;
;; Usage in platform bridge:
;;   (defn -tick [this]
;;     (dispatcher/safe-tick! (get-clojure-container this)))
;;
;;   (defn -stillValid [this player]
;;     (dispatcher/safe-validate (get-clojure-container this) player))
