(ns cn.li.ac.wireless.gui.container-dispatcher
  "Platform-agnostic container operation dispatcher
  
  This namespace provides unified interfaces for container operations,
  eliminating the need for platform-specific instanceof checks and cond branching.
  
  Uses protocols to achieve polymorphic dispatch based on container type."
  (:require [cn.li.ac.wireless.gui.node-container :as node-container]
            [cn.li.ac.wireless.gui.matrix-container :as matrix-container]
            [cn.li.ac.wireless.gui.solar-container :as solar-container]
            [cn.li.mcmod.util.log :as log]))

  (defn node-container?
    "Type check via :container-type — stable regardless of field changes."
    [container]
    (= (:container-type container) :node))

  (defn matrix-container?
    "Type check via :container-type — stable regardless of field changes."
    [container]
    (= (:container-type container) :matrix))

  (defn solar-container?
    "Type check via :container-type — stable regardless of field changes."
    [container]
    (= (:container-type container) :solar))

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
    "Synchronize container data to client")
  
  (handle-button-click! [container button-id player]
    "Handle button click from client")
  
  (handle-text-input! [container field-id text player]
    "Handle text input from client")
  
  (close-container! [container]
    "Close container and perform cleanup"))

;; ============================================================================
;; Protocol Implementations
;; ============================================================================

(extend-protocol IContainerOperations
  Object
  (tick-container! [container]
    (cond
      (node-container? container) (node-container/tick! container)
      (matrix-container? container) (matrix-container/tick! container)
      (solar-container? container) (solar-container/tick! container)
      :else (log/warn "Unknown container type for tick:" (type container))))
  
  (validate-container [container player]
    (cond
      (node-container? container) (node-container/still-valid? container player)
      (matrix-container? container) (matrix-container/still-valid? container player)
      (solar-container? container) (solar-container/still-valid? container player)
      :else false))
  
  (sync-container! [container]
    (cond
      (node-container? container) (node-container/sync-to-client! container)
      (matrix-container? container) (matrix-container/sync-to-client! container)
      (solar-container? container) (solar-container/sync-to-client! container)
      :else (log/warn "Unknown container type for sync:" (type container))))
  
  (handle-button-click! [container button-id player]
    (cond
      (node-container? container) (node-container/handle-button-click! container button-id player)
      (matrix-container? container) (matrix-container/handle-button-click! container button-id player)
      (solar-container? container) (solar-container/handle-button-click! container button-id player)
      :else (log/warn "Unknown container type for button click:" (type container))))
  
  (handle-text-input! [container _field-id _text _player]
    (log/warn "handle-text-input! not implemented for container type:" (type container)))
  
  (close-container! [container]
    (cond
      (node-container? container) (node-container/on-close container)
      (matrix-container? container) (matrix-container/on-close container)
      (solar-container? container) (solar-container/on-close container)
      :else (log/warn "Unknown container type for close:" (type container)))))

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
    (node-container? container)
    :node
    
    (matrix-container? container)
    :matrix

    (solar-container? container)
    :solar
    
    :else
    (do
      (log/warn "Unknown container type:" (type container))
      :unknown)))

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
      (log/error "Error ticking container:" ((ex-message e)))
      false)))

(defn safe-validate
  "Safely validate container with error handling
  
  Args:
  - container: Any container
  - player: Player entity
  
  Returns: boolean (default false on error). Never returns nil."
  [container player]
  (try
    (boolean (validate-container container player))
    (catch Exception e
      (log/error "Error validating container:" ((ex-message e)))
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
      (log/error "Error syncing container:" ((ex-message e)))
      false)))

(defn safe-handle-button-click!
  "Safely handle button click with error handling
  
  Args:
  - container: Any container
  - button-id: int
  - player: Player entity
  
  Returns: true if successful, false if error"
  [container button-id player]
  (try
    (handle-button-click! container button-id player)
    true
    (catch Exception e
      (log/error "Error handling button click:" ((ex-message e)))
      false)))

(defn safe-handle-text-input!
  "Safely handle text input with error handling
  
  Args:
  - container: Any container
  - field-id: int
  - text: string
  - player: Player entity
  
  Returns: true if successful, false if error"
  [container field-id text player]
  (try
    (handle-text-input! container field-id text player)
    true
    (catch Exception e
      (log/error "Error handling text input:" ((ex-message e)))
      false)))

(defn safe-close!
  "Safely close container with error handling
  
  Args:
  - container: Any container implementing IContainerOperations
  
  Returns: true if successful, false if error"
  [container]
  (try
    (close-container! container)
    true
    (catch Exception e
      (log/error "Error closing container:" ((ex-message e)))
      false)))

;; ============================================================================
;; Slot Operation Dispatch (for platform menu bridges)
;; ============================================================================

(defn slot-count
  "Get tile slot count for a container. Returns 0 for unknown containers."
  [container]
  (try
    (cond
      (node-container? container) (node-container/get-slot-count container)
      (matrix-container? container) (matrix-container/get-slot-count container)
      (solar-container? container) (solar-container/get-slot-count container)
      :else 0)
    (catch Exception e
      (log/error "Error getting slot count:" ((ex-message e)))
      0)))

(defn slot-get-item
  "Get slot item as ItemStack (or nil/empty when unavailable)."
  [container slot-index]
  (try
    (cond
      (node-container? container) (node-container/get-slot-item container slot-index)
      (matrix-container? container) (matrix-container/get-slot-item container slot-index)
      (solar-container? container) (solar-container/get-slot-item container slot-index)
      :else nil)
    (catch Exception e
      (log/error "Error getting slot item:" ((ex-message e)))
      nil)))

(defn slot-set-item!
  "Set slot item. Nil stack clears the slot."
  [container slot-index item-stack]
  (try
    (cond
      (node-container? container) (node-container/set-slot-item! container slot-index item-stack)
      (matrix-container? container) (matrix-container/set-slot-item! container slot-index item-stack)
      (solar-container? container) (solar-container/set-slot-item! container slot-index item-stack)
      :else nil)
    (catch Exception e
      (log/error "Error setting slot item:" ((ex-message e)))
      nil)))

(defn slot-can-place?
  "Check whether a stack may be placed in a slot."
  [container slot-index item-stack]
  (try
    (cond
      (node-container? container) (boolean (node-container/can-place-item? container slot-index item-stack))
      (matrix-container? container) (boolean (matrix-container/can-place-item? container slot-index item-stack))
      (solar-container? container) (boolean (solar-container/can-place-item? container slot-index item-stack))
      :else true)
    (catch Exception e
      (log/error "Error checking slot placement:" ((ex-message e)))
      false)))

(defn slot-changed!
  "Notify container that slot content changed."
  [container slot-index]
  (try
    (cond
      (node-container? container) (node-container/slot-changed! container slot-index)
      (matrix-container? container) (matrix-container/slot-changed! container slot-index)
      (solar-container? container) (solar-container/slot-changed! container slot-index)
      :else nil)
    (catch Exception e
      (log/error "Error in slot changed notification:" ((ex-message e)))
      nil)))

;; ============================================================================
;; Design Notes
;; ============================================================================;

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
