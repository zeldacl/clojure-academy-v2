(ns cn.li.ac.wireless.gui.container-dispatcher
  "Platform-agnostic container operation dispatcher

  This namespace provides unified interfaces for container operations,
  eliminating the need for platform-specific instanceof checks and cond branching.

  Uses metadata-driven dispatch via GUI registry."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.dsl :as gui-dsl]))


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
    (if-let [cfg (gui-dsl/get-config-by-container container)]
      (when-let [f (:tick-fn cfg)] (f container))
      (log/warn "Unknown container type for tick")))

  (validate-container [container player]
    (if-let [cfg (gui-dsl/get-config-by-container container)]
      (if-let [f (:validate-fn cfg)]
        (boolean (f container player))
        true)
      false))

  (sync-container! [container]
    (if-let [cfg (gui-dsl/get-config-by-container container)]
      (when-let [f (:sync-get cfg)] (f container))
      (log/warn "Unknown container type for sync")))

  (handle-button-click! [container button-id player]
    (if-let [cfg (gui-dsl/get-config-by-container container)]
      (when-let [f (:button-click-fn cfg)] (f container button-id player))
      (log/warn "Unknown container type for button click")))

  (handle-text-input! [container field-id text player]
    (if-let [cfg (gui-dsl/get-config-by-container container)]
      (when-let [f (:text-input-fn cfg)] (f container field-id text player))
      (log/warn "Unknown container type for text input")))

  (close-container! [container]
    (if-let [cfg (gui-dsl/get-config-by-container container)]
      (when-let [f (:close-fn cfg)] (f container))
      (log/warn "Unknown container type for close"))))

;; ============================================================================
;; Container Type Queries
;; ============================================================================

(defn get-container-type
  "Get the type identifier for a container

  Args:
  - container: NodeContainer or MatrixContainer

  Returns: :node or :matrix"
  [container]
  (if-let [cfg (gui-dsl/get-config-by-container container)]
    (:gui-type cfg)
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
      (log/error "Error ticking container:"(ex-message e))
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
      (log/error "Error validating container:"(ex-message e))
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
      (log/error "Error syncing container:"(ex-message e))
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
      (log/error "Error handling button click:"(ex-message e))
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
      (log/error "Error handling text input:"(ex-message e))
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
      (log/error "Error closing container:"(ex-message e))
      false)))

;; ============================================================================
;; Slot Operation Dispatch (for platform menu bridges)
;; ============================================================================

(defn slot-count
  "Get tile slot count for a container. Returns 0 for unknown containers."
  [container]
  (try
    (if-let [cfg (gui-dsl/get-config-by-container container)]
      (if-let [f (:slot-count-fn cfg)]
        (f container)
        0)
      0)
    (catch Exception e
      (log/error "Error getting slot count:"(ex-message e))
      0)))

(defn slot-get-item
  "Get slot item as ItemStack (or nil/empty when unavailable)."
  [container slot-index]
  (try
    (when-let [cfg (gui-dsl/get-config-by-container container)]
      (when-let [f (:slot-get-fn cfg)]
        (f container slot-index)))
    (catch Exception e
      (log/error "Error getting slot item:"(ex-message e))
      nil)))

(defn slot-set-item!
  "Set slot item. Nil stack clears the slot."
  [container slot-index item-stack]
  (try
    (when-let [cfg (gui-dsl/get-config-by-container container)]
      (when-let [f (:slot-set-fn cfg)]
        (f container slot-index item-stack)))
    (catch Exception e
      (log/error "Error setting slot item:"(ex-message e))
      nil)))

(defn slot-can-place?
  "Check whether a stack may be placed in a slot."
  [container slot-index item-stack]
  (try
    (if-let [cfg (gui-dsl/get-config-by-container container)]
      (if-let [f (:slot-can-place-fn cfg)]
        (boolean (f container slot-index item-stack))
        true)
      false)
    (catch Exception e
      (log/error "Error checking slot placement:"(ex-message e))
      false)))

(defn slot-changed!
  "Notify container that slot content changed."
  [container slot-index]
  (try
    (when-let [cfg (gui-dsl/get-config-by-container container)]
      (when-let [f (:slot-changed-fn cfg)]
        (f container slot-index)))
    (catch Exception e
      (log/error "Error in slot changed notification:"(ex-message e))
      nil)))

;; ============================================================================
;; Design Notes
;; ============================================================================;

;; This dispatcher provides:
;;
;; 1. **Metadata-Driven Dispatch**:
;;    - Uses GUI registry to look up handlers by container predicate
;;    - No hardcoded type checks or cond branching needed
;;    - Extensible: new container types just register via defgui
;;
;; 2. **Unified Interface**:
;;    - tick-container! - Lifecycle tick
;;    - validate-container - Permission check
;;    - sync-container! - Data synchronization
;;    - handle-button-click! - Button interactions
;;    - handle-text-input! - Text field interactions
;;    - close-container! - Cleanup on close
;;
;; 3. **Slot Operations**:
;;    - slot-count, slot-get-item, slot-set-item!
;;    - slot-can-place?, slot-changed!
;;
;; 4. **Type Queries**:
;;    - get-container-type: returns :gui-type from metadata
;;
;; 5. **Error Handling**:
;;    - safe-* wrappers catch exceptions
;;    - Return sensible defaults on error
;;    - Log errors for debugging
;;
;; Benefits:
;; - Zero dispatcher changes when adding new GUIs
;; - Single source of truth in defgui declarations
;; - Clean separation of concerns
;; - Easy to add new container types
;;
;; Usage in platform bridge:
;;   (defn -tick [this]
;;     (dispatcher/safe-tick! (get-clojure-container this)))
;;
;;   (defn -stillValid [this player]
;;     (dispatcher/safe-validate (get-clojure-container this) player))
