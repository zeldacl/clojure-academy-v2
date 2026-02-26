(ns my-mod.gui.platform-adapter
  "Platform Adapter for GUI framework
  
  This module serves as the single import point for platform-specific GUI code
  (forge/fabric bridges). It aggregates and re-exports all GUI framework functionality
  without exposing the details of the underlying wireless.gui implementation.
  
  Platform layers should ONLY import from this module, not directly from my-mod.wireless.gui.
  This allows the wireless implementation to change without affecting platform code.
  
  **Usage in platform code:**
  ```clojure
  (:require [my-mod.gui.platform-adapter :as gui]
           [my-mod.util.log :as log])
  
  ;; Use the unified API
  (gui/safe-tick! container)
  (gui/safe-close! container)
  (gui/get-display-name gui-id)
  ```"
  (:require [my-mod.wireless.gui.container-dispatcher :as dispatcher]
            [my-mod.wireless.gui.gui-metadata :as metadata]
            [my-mod.wireless.gui.slot-manager :as slot-mgr]
            [my-mod.wireless.gui.registry :as registry]
            [my-mod.wireless.gui.matrix-sync :as matrix-sync]
            [my-mod.wireless.gui.node-sync :as node-sync]
            [my-mod.wireless.gui.screen-factory :as screen-factory]))

;; ============================================================================
;; Re-export Container Dispatcher (polymorphic operations)
;; ============================================================================

(def safe-tick! dispatcher/safe-tick!)
(def safe-validate dispatcher/safe-validate)
(def safe-sync! dispatcher/safe-sync!)
(def safe-handle-button-click! dispatcher/safe-handle-button-click!)
(def safe-handle-text-input! dispatcher/safe-handle-text-input!)
(def safe-close! dispatcher/safe-close!)

(def get-container-type dispatcher/get-container-type)
(def node-container? dispatcher/node-container?)
(def matrix-container? dispatcher/matrix-container?)

;; ============================================================================
;; Re-export GUI Metadata
;; ============================================================================

(def gui-wireless-node metadata/gui-wireless-node)
(def gui-wireless-matrix metadata/gui-wireless-matrix)
(def valid-gui-ids metadata/valid-gui-ids)

(def get-display-name metadata/get-display-name)
(def get-gui-type metadata/get-gui-type)
(def get-registry-name metadata/get-registry-name)
(def get-menu-type metadata/get-menu-type)
(def get-all-gui-ids metadata/get-all-gui-ids)
(def get-screen-factory-fn-keyword metadata/get-screen-factory-fn-keyword)
(def get-screen-factory-fn-kw metadata/get-screen-factory-fn-kw)

(def gui-slot-layouts metadata/gui-slot-layouts)
(def get-slot-layout metadata/get-slot-layout)
(def get-slot-range metadata/get-slot-range)

;; ============================================================================
;; Re-export Slot Manager
;; ============================================================================

(def get-node-slot-layout slot-mgr/get-node-slot-layout)
(def get-matrix-slot-layout slot-mgr/get-matrix-slot-layout)
(def get-slot-layout-for-container slot-mgr/get-slot-layout-for-container)
(def execute-quick-move-forge slot-mgr/execute-quick-move-forge)
(def execute-quick-move-fabric slot-mgr/execute-quick-move-fabric)

;; ============================================================================
;; Re-export GUI Registry
;; ============================================================================

(def get-gui-handler registry/get-gui-handler)
(def init-gui-handler! registry/init-gui-handler!)
(def register-active-container! registry/register-active-container!)
(def unregister-active-container! registry/unregister-active-container!)
(def register-player-container! registry/register-player-container!)
(def unregister-player-container! registry/unregister-player-container!)
(def get-active-container registry/get-active-container)
(def list-active-containers registry/list-active-containers)
(def get-player-container registry/get-player-container)
(def get-client-container registry/get-client-container)
(def set-client-container! registry/set-client-container!)
(def clear-client-container! registry/clear-client-container!)
(def apply-container-sync-packet registry/apply-container-sync-packet)
(def client-container registry/client-container)

;; ============================================================================
;; Re-export Sync Modules (Matrix and Node state synchronization)
;; ============================================================================

;; Matrix state sync
(def register-matrix-sync-impl! matrix-sync/register-sync-impl!)
(def get-matrix-sync-impl matrix-sync/get-sync-impl)
(def broadcast-matrix-state matrix-sync/broadcast-matrix-state)
(def make-matrix-sync-packet matrix-sync/make-sync-packet)

;; Node state sync
(def register-node-sync-impl! node-sync/register-sync-impl!)
(def get-node-sync-impl node-sync/get-sync-impl)
(def broadcast-node-state node-sync/broadcast-node-state)
(def make-node-sync-packet node-sync/make-sync-packet)

;; ============================================================================
;; Re-export Screen Factory (screen creation on client)
;; ============================================================================

(def create-node-screen screen-factory/create-node-screen)
(def create-matrix-screen screen-factory/create-matrix-screen)

;; ============================================================================
;; Adapter Guarantees
;; ============================================================================

;; This adapter guarantees that platform code:
;; 1. Never directly imports my-mod.wireless.gui.* modules
;; 2. Only depends on this single unified API
;; 3. Remains isolated from wireless implementation details
;;
;; Benefits:
;; - Platform code has zero wireless knowledge
;; - Code reuse across Forge/Fabric platforms
;; - Easy to maintain and extend GUI framework
;; - Implementation changes don't ripple to platform code
