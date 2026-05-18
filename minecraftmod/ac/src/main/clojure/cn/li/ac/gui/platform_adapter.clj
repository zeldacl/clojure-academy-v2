(ns cn.li.ac.gui.platform-adapter
  "Platform Adapter for GUI framework
  
  This module aggregates and re-exports AC GUI framework functionality without
  exposing wireless implementation details.
  
  Loader modules must not import this namespace directly. AC installs these
  callbacks into mcmod during content activation."
  (:require [cn.li.ac.gui.platform-adapter.dispatcher-api :as dispatcher-api]
            [cn.li.ac.gui.platform-adapter.metadata-api :as metadata-api]
            [cn.li.ac.gui.platform-adapter.registry-api :as registry-api]
            [cn.li.ac.gui.platform-adapter.sync-api :as sync-api]
            [cn.li.ac.gui.slot-validators :as slot-validators]
            [cn.li.ac.wireless.gui.screen-factory :as screen-factory]
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
(def valid-gui-ids metadata-api/valid-gui-ids)

(def get-display-name metadata-api/get-display-name)
(def get-gui-type metadata-api/get-gui-type)
(def get-registry-name metadata-api/get-registry-name)
(def get-menu-type metadata-api/get-menu-type)
(def register-menu-type! metadata-api/register-menu-type!)
(def get-all-gui-ids metadata-api/get-all-gui-ids)
(def get-screen-factory-fn metadata-api/get-screen-factory-fn)
(def get-screen-factory-fn-keyword metadata-api/get-screen-factory-fn-keyword)
(def get-screen-factory-fn-kw metadata-api/get-screen-factory-fn-kw)

(def gui-slot-layouts metadata-api/gui-slot-layouts)
(def get-slot-layout metadata-api/get-slot-layout)
(def get-slot-range metadata-api/get-slot-range)

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

(def get-gui-handler registry-api/get-gui-handler)
(def register-gui-handler registry-api/register-gui-handler)
(def init-gui-handler! registry-api/init-gui-handler!)
(def register-active-container! registry-api/register-active-container!)
(def unregister-active-container! registry-api/unregister-active-container!)
(def register-player-container! registry-api/register-player-container!)
(def unregister-player-container! registry-api/unregister-player-container!)
(def get-active-container registry-api/get-active-container)
(def list-active-containers registry-api/list-active-containers)
(def get-player-container registry-api/get-player-container)
(def get-player-container-from-active registry-api/get-player-container-from-active)
(def get-container-for-menu registry-api/get-container-for-menu)
(def register-menu-container! registry-api/register-menu-container!)
(def unregister-menu-container! registry-api/unregister-menu-container!)
(def register-container-by-id! registry-api/register-container-by-id!)
(def unregister-container-by-id! registry-api/unregister-container-by-id!)
(def get-container-by-id registry-api/get-container-by-id)
(def get-menu-container-id registry-api/get-menu-container-id)
(def get-client-container registry-api/get-client-container)
(def set-client-container! registry-api/set-client-container!)
(def clear-client-container! registry-api/clear-client-container!)
(def apply-container-sync-packet registry-api/apply-container-sync-packet)
(def client-container registry-api/client-container)

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
  (gui-core/register-gui-platform-impl!
    {:register-menu-type! register-menu-type!
     :get-menu-type get-menu-type
     :set-client-container! set-client-container!
     :clear-client-container! clear-client-container!
     :get-client-container get-client-container
     :register-active-container! register-active-container!
     :unregister-active-container! unregister-active-container!
     :register-player-container! register-player-container!
     :unregister-player-container! unregister-player-container!
     :get-player-container get-player-container
     :get-player-container-from-active get-player-container-from-active
     :get-container-for-menu get-container-for-menu
     :get-container-by-id get-container-by-id
     :get-menu-container-id get-menu-container-id
     :register-menu-container! register-menu-container!
     :unregister-menu-container! unregister-menu-container!
     :register-container-by-id! register-container-by-id!
     :unregister-container-by-id! unregister-container-by-id!
     :safe-tick! safe-tick!
     :safe-validate safe-validate
     :safe-sync! safe-sync!
     :safe-close! safe-close!
     :slot-count slot-count
     :slot-get-item slot-get-item
     :slot-set-item! slot-set-item!
     :slot-changed! slot-changed!
     :slot-can-place? slot-can-place?
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
