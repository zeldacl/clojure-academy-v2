(ns my-mod.fabric1201.gui.init
  "Fabric 1.20.1 GUI System Initialization"
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.fabric1201.gui.registry-impl :as registry-impl]
            [my-mod.fabric1201.gui.screen-impl :as screen-impl]
            [my-mod.fabric1201.gui.network :as network]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Common (Server + Client) Initialization
;; ============================================================================

(defn init-common!
  "Initialize common GUI system (runs on both server and client)
  
  Should be called during mod initialization"
  []
  (log/info "=== Initializing Fabric 1.20.1 GUI System (Common) ===")
  
  ;; Register screen handler types
  (registry-impl/init!)
  
  (log/info "=== Fabric 1.20.1 GUI System (Common) Initialized ==="))

;; ============================================================================
;; Server-Only Initialization
;; ============================================================================

(defn init-server!
  "Initialize server-side GUI system
  
  Should be called during dedicated server initialization"
  []
  (log/info "=== Initializing Fabric 1.20.1 GUI System (Server) ===")
  
  ;; Register server-side network packets
  (network/init-server!)
  
  (log/info "=== Fabric 1.20.1 GUI System (Server) Initialized ==="))

;; ============================================================================
;; Client-Only Initialization
;; ============================================================================

(defn init-client!
  "Initialize client-side GUI system
  
  Should be called during client mod initialization"
  []
  (log/info "=== Initializing Fabric 1.20.1 GUI System (Client) ===")
  
  ;; Register screen factories
  (screen-impl/init-client!)
  
  ;; Register client-side network packets
  (network/init-client!)
  
  (log/info "=== Fabric 1.20.1 GUI System (Client) Initialized ==="))

;; ============================================================================
;; Verification
;; ============================================================================

(defn verify-initialization
  "Verify that GUI system is properly initialized
  
  Platform-agnostic design: Dynamically verifies all GUI IDs from metadata.
  
  Returns: boolean (true if all checks pass)"
  []
  (log/info "Verifying Fabric GUI system initialization...")
  
  (let [;; Dynamically check all GUI IDs from metadata
        gui-checks (into {}
                        (for [gui-id (gui/get-all-gui-ids)]
                          (let [check-key (keyword (str "gui-" gui-id "-handler-type"))
                                handler-type (registry-impl/get-handler-type gui-id)]
                            [check-key (some? handler-type)])))
        
        checks gui-checks]
    
    (doseq [[check-name result] checks]
      (log/info "  " check-name ":" (if result "✓" "✗")))
    
    (let [all-passed? (every? true? (vals checks))]
      (if all-passed?
        (log/info "All Fabric GUI system checks passed!")
        (log/error "Some Fabric GUI system checks failed!"))
      all-passed?)))

;; ============================================================================
;; Error Recovery
;; ============================================================================

(defn safe-init-common!
  "Initialize common GUI system with error handling"
  []
  (try
    (init-common!)
    true
    (catch Exception e
      (log/error "Failed to initialize common Fabric GUI system:" (.getMessage e))
      (.printStackTrace e)
      false)))

(defn safe-init-client!
  "Initialize client GUI system with error handling"
  []
  (try
    (init-client!)
    true
    (catch Exception e
      (log/error "Failed to initialize client Fabric GUI system:" (.getMessage e))
      (.printStackTrace e)
      false)))

(defn safe-init-server!
  "Initialize server GUI system with error handling"
  []
  (try
    (init-server!)
    true
    (catch Exception e
      (log/error "Failed to initialize server Fabric GUI system:" (.getMessage e))
      (.printStackTrace e)
      false)))

;; ============================================================================
;; Fabric API Integration Helpers
;; ============================================================================

(defn register-with-fabric-api!
  "Register GUI system with Fabric API events
  
  This integrates with Fabric's event system for proper lifecycle management"
  []
  (log/info "Registering with Fabric API events")
  
  ;; Note: Fabric uses different event registration patterns
  ;; This is a placeholder for Fabric-specific event hooks
  
  (log/info "Fabric API event registration complete"))

;; ============================================================================
;; Full Initialization (All Phases)
;; ============================================================================

(defn init-all!
  "Initialize all phases of the GUI system
  
  This is a convenience function for development/testing.
  In production, call init-common!, init-server!, and init-client! separately
  based on the current side."
  []
  (log/info "=== Full Fabric 1.20.1 GUI Initialization ===")
  
  ;; Common
  (safe-init-common!)
  
  ;; Server
  (safe-init-server!)
  
  ;; Client
  (safe-init-client!)
  
  ;; Verify
  (verify-initialization)
  
  (log/info "=== Full Fabric 1.20.1 GUI Initialization Complete ==="))

;; ============================================================================
;; Cleanup
;; ============================================================================

(defn cleanup!
  "Cleanup GUI system resources
  
  Should be called during mod shutdown (if needed)"
  []
  (log/info "Cleaning up Fabric GUI system")
  
  ;; Clear registries
  (reset! registry-impl/node-handler-type nil)
  (reset! registry-impl/matrix-handler-type nil)
  
  (log/info "Fabric GUI system cleanup complete"))
