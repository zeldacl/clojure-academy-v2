(ns cn.li.forge1201.gui.init
  "Forge 1.20.1 GUI System Initialization"
  (:require [cn.li.mc1201.gui.init-orchestrator :as gui-orchestrator]
            [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mcmod.util.log :as log]))

(defn init-common!
  "Initialize common GUI system (server + client).
  MenuType registration is handled earlier via DeferredRegister in mod-init;
  only non-registry setup belongs here."
  []
  (gui-orchestrator/phase-start! "Forge 1.20.1" "Common")
  (if-let [network-init! (requiring-resolve 'cn.li.forge1201.gui.network/init!)]
    (network-init!)
    (log/warn "Forge GUI network init fn not available"))
  (gui-orchestrator/phase-done! "Forge 1.20.1" "Common"))

;; ============================================================================
;; Client-Only Initialization
;; ============================================================================

(defn init-client!
  "Initialize client-side GUI system
  
  Should be called during FMLClientSetupEvent"
  []
  (gui-orchestrator/phase-start! "Forge 1.20.1" "Client")
  
  ;; Register screen factories
  (if-let [init-screen! (requiring-resolve 'cn.li.forge1201.gui.screen-impl/init-client!)]
    (init-screen!)
    (log/warn "Forge GUI screen impl not available on current side"))
  
  (gui-orchestrator/phase-done! "Forge 1.20.1" "Client"))

;; ============================================================================
;; Server-Only Initialization
;; ============================================================================

(defn init-server!
  "Initialize server-side GUI system
  
  Should be called during FMLDedicatedServerSetupEvent"
  []
  (gui-orchestrator/phase-start! "Forge 1.20.1" "Server")
  
  ;; Server-specific initialization (if needed)
  ;; Currently all server logic is in common
  
  (gui-orchestrator/phase-done! "Forge 1.20.1" "Server"))

;; ============================================================================
;; Verification
;; ============================================================================

(defn verify-initialization
  "Verify that GUI system is properly initialized
  
  Platform-agnostic design: Dynamically verifies all GUI IDs from metadata.
  
  Returns: boolean (true if all checks pass)"
  []
  (let [;; Dynamically check all GUI IDs from metadata
      get-menu-type (requiring-resolve 'cn.li.forge1201.gui.registry-impl/get-menu-type)
        checks (into {}
                    (for [gui-id (gui/get-all-gui-ids)]
                      (let [check-key (keyword (str "gui-" gui-id "-menu-type"))
              menu-type (when get-menu-type (get-menu-type gui-id))]
                        [check-key (some? menu-type)])))]
    (gui-orchestrator/verify-checks! "Verifying GUI system initialization..." checks)))

;; ============================================================================
;; Error Recovery
;; ============================================================================

(defn safe-init-common!
  "Initialize common GUI system with error handling"
  []
  (gui-orchestrator/safe-init! "Failed to initialize common GUI system:" init-common!))

(defn safe-init-client!
  "Initialize client GUI system with error handling"
  []
  (gui-orchestrator/safe-init! "Failed to initialize client GUI system:" init-client!))

(defn safe-init-server!
  "Initialize server GUI system with error handling"
  []
  (gui-orchestrator/safe-init! "Failed to initialize server GUI system:" init-server!))
