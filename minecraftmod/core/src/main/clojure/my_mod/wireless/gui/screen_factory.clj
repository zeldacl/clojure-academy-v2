(ns my-mod.wireless.gui.screen-factory
  "Platform-agnostic screen factory for Wireless GUI system
  
  This namespace contains the core game logic for creating GUI screens,
  independent of platform-specific implementation details (Forge/Fabric).
  
  Platform-specific screen_impl.clj files should delegate to this factory
  and only handle platform-specific registration mechanics."
  (:require [my-mod.wireless.gui.node-gui :as node-gui]
            [my-mod.wireless.gui.matrix-gui :as matrix-gui]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Screen Creation - Core Game Logic
;; ============================================================================

(defn create-node-screen
  "Create Node GUI screen (platform-agnostic)
  
  This function contains the core game logic for creating a Node screen:
  1. Extract Clojure container from platform wrapper
  2. Delegate to node-gui/create-screen for CGui creation
  3. Handle errors gracefully
  
  Args:
  - container-or-handler: Platform-specific container/handler wrapper
                          Must have .getClojureContainer() method
  - player-inventory: Player inventory (not currently used but required by platform APIs)
  - title: Text component (not currently used but required by platform APIs)
  
  Returns: CGuiScreenContainer instance or nil on error"
  [container-or-handler player-inventory title]
  (log/info "Creating Node screen (platform-agnostic factory)")
  
  (try
    (let [;; Extract Clojure container from platform wrapper
          ;; Works for both Forge Container and Fabric ScreenHandler
          clj-container (.getClojureContainer container-or-handler)
          
          ;; Create CGui screen using platform-agnostic GUI code
          cgui-screen (node-gui/create-screen clj-container container-or-handler)]
      
      (log/info "Node screen created successfully")
      cgui-screen)
    
    (catch Exception e
      (log/error "Failed to create Node screen:" (.getMessage e))
      (.printStackTrace e)
      nil)))

(defn create-matrix-screen
  "Create Matrix GUI screen (platform-agnostic)
  
  This function contains the core game logic for creating a Matrix screen:
  1. Extract Clojure container from platform wrapper
  2. Delegate to matrix-gui/create-screen for CGui creation
  3. Handle errors gracefully
  
  Args:
  - container-or-handler: Platform-specific container/handler wrapper
                          Must have .getClojureContainer() method
  - player-inventory: Player inventory (not currently used but required by platform APIs)
  - title: Text component (not currently used but required by platform APIs)
  
  Returns: CGuiScreenContainer instance or nil on error"
  [container-or-handler player-inventory title]
  (log/info "Creating Matrix screen (platform-agnostic factory)")
  
  (try
    (let [;; Extract Clojure container from platform wrapper
          clj-container (.getClojureContainer container-or-handler)
          
          ;; Create CGui screen
          cgui-screen (matrix-gui/create-screen clj-container container-or-handler)]
      
      (log/info "Matrix screen created successfully")
      cgui-screen)
    
    (catch Exception e
      (log/error "Failed to create Matrix screen:" (.getMessage e))
      (.printStackTrace e)
      nil)))

;; ============================================================================
;; Design Notes
;; ============================================================================

;; This factory provides a clean separation between:
;;
;; 1. **Game Logic** (this namespace):
;;    - Extracting Clojure containers from platform wrappers
;;    - Delegating to CGui-based GUI creation
;;    - Error handling and logging
;;
;; 2. **Platform-Specific Code** (screen_impl.clj in each platform):
;;    - Registering screen factories with platform APIs
;;    - Handling platform-specific types and interfaces
;;    - Platform initialization lifecycle
;;
;; Benefits:
;; - DRY: No duplicate game logic across platforms
;; - Testability: Core logic testable without platform dependencies
;; - Maintainability: Changes to screen creation logic in one place
;; - Clarity: Clear boundary between game logic and platform integration
