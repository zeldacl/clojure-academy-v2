(ns my-mod.forge1165.gui.init
  "Forge 1.16.5 GUI System Initialization"
  (:require [my-mod.forge1165.gui.registry-impl :as registry-impl]
            [my-mod.forge1165.gui.screen-impl :as screen-impl]
            [my-mod.forge1165.gui.network :as network]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Common (Server + Client) Initialization
;; ============================================================================

(defn init-common!
  "Initialize common GUI system (runs on both server and client)
  
  Should be called during FMLCommonSetupEvent"
  []
  (log/info "=== Initializing Forge 1.16.5 GUI System (Common) ===")
  
  ;; Register network packets
  (network/init!)
  
  ;; Register menu types
  (registry-impl/init!)
  
  (log/info "=== Forge 1.16.5 GUI System (Common) Initialized ==="))

;; ============================================================================
;; Client-Only Initialization
;; ============================================================================

(defn init-client!
  "Initialize client-side GUI system
  
  Should be called during FMLClientSetupEvent"
  []
  (log/info "=== Initializing Forge 1.16.5 GUI System (Client) ===")
  
  ;; Register screen factories
  (screen-impl/init-client!)
  
  (log/info "=== Forge 1.16.5 GUI System (Client) Initialized ==="))

;; ============================================================================
;; Server-Only Initialization
;; ============================================================================

(defn init-server!
  "Initialize server-side GUI system
  
  Should be called during FMLDedicatedServerSetupEvent"
  []
  (log/info "=== Initializing Forge 1.16.5 GUI System (Server) ===")
  
  ;; Server-specific initialization (if needed)
  ;; Currently all server logic is in common
  
  (log/info "=== Forge 1.16.5 GUI System (Server) Initialized ==="))

;; ============================================================================
;; Verification
;; ============================================================================

(defn verify-initialization
  "Verify that GUI system is properly initialized
  
  Returns: boolean (true if all checks pass)"
  []
  (log/info "Verifying GUI system initialization...")
  
  (let [checks
        {:network-channel (some? @network/network-channel)
         :node-menu-type (some? @registry-impl/node-menu-type)
         :matrix-menu-type (some? @registry-impl/matrix-menu-type)}]
    
    (doseq [[check-name result] checks]
      (log/info "  " check-name ":" (if result "✓" "✗")))
    
    (let [all-passed? (every? true? (vals checks))]
      (if all-passed?
        (log/info "All GUI system checks passed!")
        (log/error "Some GUI system checks failed!"))
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
      (log/error "Failed to initialize common GUI system:" (.getMessage e))
      (.printStackTrace e)
      false)))

(defn safe-init-client!
  "Initialize client GUI system with error handling"
  []
  (try
    (init-client!)
    true
    (catch Exception e
      (log/error "Failed to initialize client GUI system:" (.getMessage e))
      (.printStackTrace e)
      false)))

(defn safe-init-server!
  "Initialize server GUI system with error handling"
  []
  (try
    (init-server!)
    true
    (catch Exception e
      (log/error "Failed to initialize server GUI system:" (.getMessage e))
      (.printStackTrace e)
      false)))
