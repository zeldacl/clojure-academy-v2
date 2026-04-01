(ns cn.li.ac.wireless.gui.screen-factory
  "Platform-agnostic screen factory for Wireless GUI system
  
  This namespace contains the core game logic for creating GUI screens,
  independent of platform-specific implementation details (Forge/Fabric).
  
  Platform-specific screen_impl.clj files should delegate to this factory
  and only handle platform-specific registration mechanics."
  (:require [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.ac.wireless.gui.registry :as gui-registry]
            [cn.li.mcmod.platform.entity :as entity]
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
  (log/info "[SCREEN-FACTORY-CORE] Creating" (name gui-type) "screen (platform-agnostic factory)")
  (log/info "[SCREEN-FACTORY-CORE] gui-type=" gui-type "container=" (type container-or-handler))
  
  (try
    (log/info "[SCREEN-FACTORY-CORE] Getting GUI config for gui-type:" gui-type)
    (let [cfg (gui-dsl/get-gui-by-type gui-type)
          _ (when-not cfg
              (throw (ex-info "Unknown gui-type" {:gui-type gui-type})))
        gui-id (gui-dsl/get-gui-id-for-type gui-type)
        _ (log/info "[SCREEN-FACTORY-CORE] gui-id=" gui-id "cfg found=" (not (nil? cfg)))
        screen-fn (or (when gui-id (gui-dsl/get-screen-fn gui-id))
              (:screen-fn cfg))
        _ (when-not screen-fn
          (throw (ex-info "GUI has no screen factory function"
                  {:gui-type gui-type :gui-id gui-id})))
        _ (log/info "[SCREEN-FACTORY-CORE] screen-fn resolved=" (if screen-fn "YES" "NO"))
          ;; The Clojure container was stored by the client-side IContainerFactory
          ;; (registry_impl.clj) just before this screen factory is invoked.
          ;; Previously the gen-class ForgeMenuBridge had getClojureContainer(),
          ;; but the proxy replacement has no such method.
        _ (log/info "[SCREEN-FACTORY-CORE] Getting client container...")
        clj-container (or (gui-registry/get-client-container)
                            (throw (ex-info "No client container registered for screen creation"
                                            {:gui-type gui-type})))
        _ (log/info "[SCREEN-FACTORY-CORE] Got clj-container=" (type clj-container))
        player (entity/inventory-get-player player-inventory)
        _ (log/info "[SCREEN-FACTORY-CORE] Got player=" (.getName player))
        _ (log/info "[SCREEN-FACTORY-CORE] Calling screen-fn...")
        cgui-screen (screen-fn clj-container container-or-handler player)]
      
      (log/info "[SCREEN-FACTORY-CORE] Screen function returned:" (type cgui-screen))
      (log/info "[SCREEN-FACTORY-CORE] " (name gui-type) " screen created successfully")
      cgui-screen)
    
    (catch Throwable e
      (log/error "[SCREEN-FACTORY-CORE] Failed to create " (name gui-type) " screen:" (ex-message e))
      (log/error "[SCREEN-FACTORY-CORE] Exception:" e)
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
