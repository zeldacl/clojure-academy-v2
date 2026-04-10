(ns cn.li.forge1201.gui.init
  "Forge 1.20.1 GUI System Initialization"
  (:require [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mcmod.util.log :as log]))

(defn init-common!
  "Initialize common GUI system (server + client).
  MenuType registration is handled earlier via DeferredRegister in mod-init;
  only non-registry setup belongs here."
  []
  (log/info "=== Initializing Forge 1.20.1 GUI System (Common) ===")
  (if-let [network-init! (requiring-resolve 'cn.li.forge1201.gui.network/init!)]
    (network-init!)
    (log/warn "Forge GUI network init fn not available"))
  (log/info "=== Forge 1.20.1 GUI System (Common) Initialized ==="))

;; ============================================================================
;; Client-Only Initialization
;; ============================================================================

(defn init-client!
  "Initialize client-side GUI system
  
  Should be called during FMLClientSetupEvent"
  []
  (log/info "=== Initializing Forge 1.20.1 GUI System (Client) ===")
  
  ;; Register screen factories
  (if-let [init-screen! (requiring-resolve 'cn.li.forge1201.gui.screen-impl/init-client!)]
    (init-screen!)
    (log/warn "Forge GUI screen impl not available on current side"))
  
  (log/info "=== Forge 1.20.1 GUI System (Client) Initialized ==="))

;; ============================================================================
;; Server-Only Initialization
;; ============================================================================

(defn init-server!
  "Initialize server-side GUI system
  
  Should be called during FMLDedicatedServerSetupEvent"
  []
  (log/info "=== Initializing Forge 1.20.1 GUI System (Server) ===")
  
  ;; Server-specific initialization (if needed)
  ;; Currently all server logic is in common
  
  (log/info "=== Forge 1.20.1 GUI System (Server) Initialized ==="))

;; ============================================================================
;; Verification
;; ============================================================================

(defn verify-initialization
  "Verify that GUI system is properly initialized
  
  Platform-agnostic design: Dynamically verifies all GUI IDs from metadata.
  
  Returns: boolean (true if all checks pass)"
  []
  (log/info "Verifying GUI system initialization...")
  
  (let [;; Dynamically check all GUI IDs from metadata
      get-menu-type (requiring-resolve 'cn.li.forge1201.gui.registry-impl/get-menu-type)
        checks (into {}
                    (for [gui-id (gui/get-all-gui-ids)]
                      (let [check-key (keyword (str "gui-" gui-id "-menu-type"))
              menu-type (when get-menu-type (get-menu-type gui-id))]
                        [check-key (some? menu-type)])))]
    
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
