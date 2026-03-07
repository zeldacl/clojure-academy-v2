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
            [my-mod.gui.dsl :as gui-dsl]
            [my-mod.wireless.gui.gui-metadata :as metadata]
            [my-mod.wireless.gui.slot-manager :as slot-mgr]
            [my-mod.wireless.gui.registry :as registry]
            [my-mod.wireless.gui.matrix-sync :as matrix-sync]
            [my-mod.wireless.gui.node-sync :as node-sync]
            [my-mod.wireless.gui.screen-factory :as screen-factory]
            [my-mod.util.log :as log]))

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
(def gui-solar-gen metadata/gui-solar-gen)
(defn valid-gui-ids
  "Return a set of all registered wireless GUI ids."
  []
  (set (metadata/get-all-gui-ids)))

(def get-display-name metadata/get-display-name)
(def get-gui-type metadata/get-gui-type)
(def get-registry-name metadata/get-registry-name)
(def get-menu-type metadata/get-menu-type)
(def get-all-gui-ids metadata/get-all-gui-ids)
(def get-screen-factory-fn metadata/get-screen-factory-fn)
(def get-screen-factory-fn-keyword metadata/get-screen-factory-fn)
(def get-screen-factory-fn-kw metadata/get-screen-factory-fn)

(defn gui-slot-layouts
  "Return a map of gui-id -> slot-layout for all registered GUIs."
  []
  (into {}
        (keep (fn [gui-id]
                (when-let [layout (metadata/get-slot-layout gui-id)]
                  [gui-id layout])))
        (metadata/get-all-gui-ids)))
(def get-slot-layout metadata/get-slot-layout)
(def get-slot-range metadata/get-slot-range)

;; ============================================================================
;; Re-export Slot Manager
;; ============================================================================

(def get-node-slot-layout slot-mgr/get-tile-slot-range)
(def get-matrix-slot-layout slot-mgr/get-tile-slot-range)
(def get-slot-layout-for-container slot-mgr/get-tile-slot-range)
(def execute-quick-move-forge slot-mgr/get-quick-move-strategy)
(def execute-quick-move-fabric slot-mgr/get-quick-move-strategy)

;; ============================================================================
;; Re-export GUI Registry
;; ============================================================================

(def get-gui-handler registry/get-gui-handler)
(def init-gui-handler! registry/init!)
(def register-active-container! registry/register-active-container!)
(def unregister-active-container! registry/unregister-active-container!)
(def register-player-container! registry/register-player-container!)
(def unregister-player-container! registry/unregister-player-container!)
(def get-active-container (fn [_player] nil))
(def list-active-containers (fn [] #{}))
(def get-player-container registry/get-player-container)
(def get-client-container registry/get-client-container)
(def set-client-container! registry/set-client-container!)
(def clear-client-container! registry/clear-client-container!)
(def apply-container-sync-packet registry/apply-container-sync-packet)
(def client-container registry/get-client-container)

;; ============================================================================
;; Re-export Sync Modules (Payload creation and application)
;; ============================================================================

;; Matrix sync packet helpers
(def make-matrix-sync-packet matrix-sync/make-sync-packet)
(def apply-matrix-sync-payload! matrix-sync/apply-matrix-sync-payload!)

;; Node sync packet helpers  
(def make-node-sync-packet node-sync/make-sync-packet)
(def apply-node-sync-payload! node-sync/apply-node-sync-payload!)

;; ============================================================================
;; Unified GUI Sync System (Platform-Business Separation)
;; ============================================================================

;; Universal broadcast function registry
;; Platform code registers ONE broadcast function that handles all GUI types
(defonce ^:private platform-broadcast-fn (atom nil))

(defn register-gui-sync-impl!
  "Register a unified GUI state broadcast implementation for the current platform.
  
  The broadcast function should accept [payload player] where:
  - payload: Map containing :gui-id and GUI-specific state data
  - player: ServerPlayerEntity to send packet to
  
  Platform code should route by gui-id internally using apply-gui-sync-payload!
  This eliminates the need to add new packet types when adding new GUIs."
  [broadcast-fn]
  (reset! platform-broadcast-fn broadcast-fn)
  (log/info "Registered unified GUI sync implementation"))

(defn get-platform-broadcast-fn
  "Get the registered platform broadcast function"
  []
  @platform-broadcast-fn)

(defn apply-gui-sync-payload!
  "Apply GUI sync payload on client by routing to correct business handler.
  
  This function is called by platform packet handlers with the decoded payload.
  It routes to the appropriate GUI-specific sync handler based on gui-id."
  [payload]
  (let [gui-id (:gui-id payload)
        spec (when (integer? gui-id) (gui-dsl/get-gui-by-gui-id gui-id))
        apply-fn (:payload-sync-apply-fn spec)]
    (if apply-fn
      (apply-fn payload)
      (log/debug "Unknown gui-id in sync payload:" gui-id))))

;; ============================================================================
;; Re-export Screen Factory (screen creation on client)
;; ============================================================================

(def create-node-screen screen-factory/create-node-screen)
(def create-matrix-screen screen-factory/create-matrix-screen)
(def create-solar-screen screen-factory/create-solar-screen)

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
