(ns cn.li.ac.gui.platform-adapter
  "Platform Adapter for GUI framework
  
  This module aggregates and re-exports AC GUI framework functionality without
  exposing wireless implementation details.
  
  Loader modules must not import this namespace directly. AC installs these
  callbacks into mcmod during content activation."
  (:require [cn.li.ac.gui.platform-adapter.dispatcher-api :as dispatcher-api]
            [cn.li.ac.gui.platform-adapter.sync-api :as sync-api]
            [cn.li.ac.gui.slot-validators :as slot-validators]
            [cn.li.ac.wireless.gui.registry :as wireless-registry]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.gui.handler :as gui-handler]
            [cn.li.mcmod.gui.metadata :as metadata]
            [cn.li.mcmod.gui.registry-core :as gui-core]))

;; ============================================================================
;; Re-export Container Dispatcher (polymorphic operations)
;; ============================================================================

(def safe-tick! dispatcher-api/safe-tick!)
(def safe-validate dispatcher-api/safe-validate)
(def safe-sync! dispatcher-api/safe-sync!)
(def safe-handle-button-click! dispatcher-api/safe-handle-button-click!)
(def safe-handle-text-input! dispatcher-api/safe-handle-text-input!)
(def safe-close! dispatcher-api/safe-close!)

;; Slot operation API for platform menu bridges
(def slot-count dispatcher-api/slot-count)
(def slot-get-item dispatcher-api/slot-get-item)
(def slot-set-item! dispatcher-api/slot-set-item!)
(def slot-can-place? dispatcher-api/slot-can-place?)
(def slot-changed! dispatcher-api/slot-changed!)

(def get-container-type dispatcher-api/get-container-type)

;; ============================================================================
;; Re-export GUI Metadata
;; ============================================================================

;; GUI IDs are now managed by DSL - use metadata/get-gui-id-for-type instead
(defn valid-gui-ids []
  (set (metadata/get-all-gui-ids)))

(def get-display-name metadata/get-display-name)
(def get-gui-type metadata/get-gui-type)
(def get-registry-name metadata/get-registry-name)
(def get-all-gui-ids metadata/get-all-gui-ids)
(def get-screen-factory-fn metadata/get-screen-factory-fn)
(def get-screen-factory-fn-keyword metadata/get-screen-factory-fn)
(def get-screen-factory-fn-kw metadata/get-screen-factory-fn)

(defn gui-slot-layouts []
  (into {}
        (keep (fn [gui-id]
                (when-let [layout (metadata/get-slot-layout gui-id)]
                  [gui-id layout])))
        (metadata/get-all-gui-ids)))

(def get-slot-layout metadata/get-slot-layout)
(def get-slot-range metadata/get-slot-range)

(def get-gui-id-for-container dispatcher-api/get-gui-id-for-container)

;; ============================================================================
;; Re-export Slot Validators
;; ============================================================================

(def energy-item-validator slot-validators/energy-item-validator)
(def constraint-plate-validator slot-validators/constraint-plate-validator)
(def matrix-core-validator slot-validators/matrix-core-validator)
(def output-slot-validator slot-validators/output-slot-validator)

;; ============================================================================
;; Re-export GUI Registry
;; ============================================================================

(def get-gui-handler gui-handler/get-gui-handler)
(def register-gui-handler wireless-registry/register-gui-handler)
(def init-gui-handler! wireless-registry/init!)
(def get-menu-container-id container-state/get-menu-container-id)
(def apply-container-sync-packet wireless-registry/apply-container-sync-packet)

;; ============================================================================
;; Unified GUI Sync System (Platform-Business Separation)
;; ============================================================================

;; Universal broadcast function registry
;; Platform code registers ONE broadcast function that handles all GUI types
(def register-gui-sync-impl! sync-api/register-gui-sync-impl!)
(def get-platform-broadcast-fn sync-api/get-platform-broadcast-fn)
(def apply-gui-sync-payload! sync-api/apply-gui-sync-payload!)

(defn install-into-mcmod!
  "Install AC GUI callbacks into the platform-neutral mcmod GUI registry.

  Platform loaders only provide loader-specific MenuType/open-screen shells;
  all AC GUI metadata/container behavior is registered by AC itself."
  []
  (slot-validators/register-default-slot-validators!)
  (gui-core/register-gui-platform-impl!
    {:safe-tick! safe-tick!
     :safe-validate safe-validate
     :safe-sync! safe-sync!
     :safe-close! safe-close!
     :slot-count slot-count
     :slot-get-item slot-get-item
     :slot-set-item! slot-set-item!
     :slot-changed! slot-changed!
     :slot-can-place? slot-can-place?
      :execute-quick-move-forge dispatcher-api/execute-quick-move-forge
     :get-container-type get-container-type
     :get-gui-id-for-container get-gui-id-for-container})
  nil)

;; ============================================================================
;; Adapter Guarantees
;; ============================================================================

;; This adapter guarantees that loader code never imports AC GUI internals.
;;
;; Benefits:
;; - Platform code has zero wireless knowledge
;; - Code reuse across Forge/Fabric platforms
;; - Easy to maintain and extend GUI framework
;; - Implementation changes don't ripple to platform code
