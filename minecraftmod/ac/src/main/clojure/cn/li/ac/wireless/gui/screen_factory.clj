(ns cn.li.ac.wireless.gui.screen-factory
  "Platform-agnostic screen factory for Wireless GUI system
  
  This namespace contains the core game logic for creating GUI screens,
  independent of platform-specific implementation details (Forge/Fabric).
  
  Platform-specific screen_impl.clj files should delegate to this factory
  and only handle platform-specific registration mechanics."
  (:require [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.ac.wireless.gui.registry :as gui-registry]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Screen Creation - Core Game Logic
;; ============================================================================

(defn create-screen
  "Generic screen creation function (platform-agnostic factory)
  
  Args:
  - gui-type: Keyword (:node, :matrix, etc.)
  - container-or-handler: Platform-specific container/handler wrapper
                          Must have .getClojureContainer() method
  - player-inventory: Player inventory (not currently used but required by platform APIs)
  - title: Text component (not currently used but required by platform APIs)
  
  Returns: CGuiScreenContainer instance or nil on error"
  [gui-type container-or-handler player-inventory title]
  (log/info "Creating" (name gui-type) "screen (platform-agnostic factory)")
  
  (try
    (let [cfg (gui-dsl/get-gui-by-type gui-type)
          _ (when-not cfg
              (throw (ex-info "Unknown gui-type" {:gui-type gui-type})))
          ;; The Clojure container was stored by the client-side IContainerFactory
          ;; (registry_impl.clj) just before this screen factory is invoked.
          ;; Previously the gen-class ForgeMenuBridge had getClojureContainer(),
          ;; but the proxy replacement has no such method.
          clj-container (or (gui-registry/get-client-container)
                            (throw (ex-info "No client container registered for screen creation"
                                            {:gui-type gui-type})))
          player (clojure.lang.Reflector/getInstanceField player-inventory "player")
          cgui-screen ((:screen-fn cfg) clj-container container-or-handler player)]
      
      (log/info (str (name gui-type) " screen created successfully"))
      cgui-screen)
    
    (catch Exception e
      (log/error (str "Failed to create " (name gui-type) " screen:") (.getMessage e))
      (.printStackTrace e)
      nil)))

(defn create-node-screen
  "Create Node GUI screen (platform-agnostic)
  
  Delegates to generic create-screen with :node configuration.
  
  Args:
  - container-or-handler: Platform-specific container/handler wrapper
  - player-inventory: Player inventory
  - title: Text component
  
  Returns: CGuiScreenContainer instance or nil on error"
  [container-or-handler player-inventory title]
  (create-screen :node container-or-handler player-inventory title))

(defn create-matrix-screen
  "Create Matrix GUI screen (platform-agnostic)
  
  Delegates to generic create-screen with :matrix configuration.
  
  Args:
  - container-or-handler: Platform-specific container/handler wrapper
  - player-inventory: Player inventory
  - title: Text component
  
  Returns: CGuiScreenContainer instance or nil on error"
  [container-or-handler player-inventory title]
  (create-screen :matrix container-or-handler player-inventory title))

(defn create-solar-screen
  "Create Solar Generator GUI screen (platform-agnostic)"
  [container-or-handler player-inventory title]
  (create-screen :solar container-or-handler player-inventory title))

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
