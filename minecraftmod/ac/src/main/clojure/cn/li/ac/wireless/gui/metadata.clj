(ns cn.li.ac.wireless.gui.metadata
  "Centralized GUI metadata management
  
  This namespace contains all GUI-related constants and mappings,
  eliminating hardcoded case statements in platform-specific code."
  (:require [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; GUI ID Constants
;; ============================================================================

;; GUI IDs are now managed by the DSL system via defgui :gui-id parameter.
;; No hardcoded constants needed here.

;; ============================================================================
;; Registration API (for new GUIs)
;; ============================================================================

(defn register-gui!
  "Register a new GUI's metadata.

  This is intended for new GUI modules so that adding a GUI does not require
  editing this file by hand each time. Existing built-in GUIs (node/matrix/solar)
  are seeded via the static maps above; register-gui! extends those maps.

  Args:
  - gui-key: keyword identifying the GUI type, e.g. :solar
  - cfg: map with keys
    {:id           int
     :display-name string
     :type         keyword
     :registry-name string
     :screen-fn-kw keyword
     :slot-layout  {:slots [...] :ranges {...}}}"
  [gui-key {:keys [id display-name type registry-name screen-fn-kw slot-layout] :as cfg}]
  (when-not (integer? id)
    (throw (ex-info "register-gui!: :id must be an integer" {:gui-key gui-key :cfg cfg})))
  (log/info "Registering GUI metadata (delegating to cn.li.mcmod.gui.dsl)" {:gui-key gui-key :id id})
  (gui-dsl/register-gui!
    (gui-dsl/create-gui-spec (name gui-key)
                             {:gui-id id
                              :display-name display-name
                              :gui-type type
                              :registry-name registry-name
                              :screen-factory-fn-kw screen-fn-kw
                              :slot-layout slot-layout}))
  nil)

;; ============================================================================
;; Query Functions
;; ============================================================================

(defn get-all-gui-ids
  "Get all registered GUI IDs
  
  Returns: seq of int"
  []
  (gui-dsl/get-all-gui-ids))

(defn valid-gui-id?
  "Check if GUI ID is valid
  
  Args:
  - gui-id: int
  
  Returns: boolean"
  [gui-id]
  (gui-dsl/has-gui-id? gui-id))

(defn get-display-name
  "Get display name for GUI ID
  
  Args:
  - gui-id: int
  
  Returns: string or \"Unknown GUI\" if invalid"
  [gui-id]
  (or (gui-dsl/get-display-name gui-id)
      "Unknown GUI"))

(defn get-gui-type
  "Get container type for GUI ID
  
  Args:
  - gui-id: int
  
  Returns: :node, :matrix, or :unknown"
  [gui-id]
  (or (gui-dsl/get-gui-type gui-id)
      :unknown))

(defn get-registry-name
  "Get registry identifier for GUI
  
  Args:
  - gui-id: int
  
  Returns: string (registry name) or \"unknown_gui\""
  [gui-id]
  (or (gui-dsl/get-registry-name gui-id)
      "unknown_gui"))

(defn get-screen-factory-fn
  "Get screen factory function keyword for GUI
  
  Args:
  - gui-id: int
  
  Returns: keyword (:create-node-screen, :create-matrix-screen, etc.) or nil"
  [gui-id]
  (gui-dsl/get-screen-factory-fn-kw gui-id))

(defn get-slot-layout
  "Get slot layout configuration for GUI
  
  Args:
  - gui-id: int
  
  Returns: map with {:slots [...] :ranges {...}} or nil"
  [gui-id]
  (gui-dsl/get-slot-layout gui-id))

(defn get-slot-range
  "Get slot index range for a GUI section
  
  Args:
  - gui-id: int
  - section: :tile, :player-main, :player-hotbar
  
  Returns: [start-index end-index] (inclusive) or [0 0] if not found"
  [gui-id section]
  (gui-dsl/get-slot-range gui-id section))

;; ============================================================================
;; Reverse Lookups
;; ============================================================================

(defn get-gui-id-for-type
  "Get GUI ID for container type

  Args:
  - container-type: :node, :matrix, :solar, etc.

  Returns: int or nil if unknown"
  [container-type]
  (gui-dsl/get-gui-id-for-type container-type))

;; ============================================================================
;; Platform-Specific Metadata Storage
;; ============================================================================

(defonce platform-menu-types
  ^{:doc "Platform-specific MenuType/ScreenHandlerType registry

  Structure: {:platform {:gui-id MenuType}}"}
  (atom {}))

(defn register-menu-type!
  "Register a platform-specific MenuType
  
  Args:
  - platform: :forge-1.16.5, :forge-1.20.1, :fabric-1.20.1
  - gui-id: int
  - menu-type: MenuType or ScreenHandlerType"
  [platform gui-id menu-type]
  (swap! platform-menu-types assoc-in [platform gui-id] menu-type)
  (log/info "Registered MenuType for" platform "GUI" gui-id))

(defn get-menu-type
  "Get MenuType for platform and GUI ID
  
  Args:
  - platform: keyword
  - gui-id: int
  
  Returns: MenuType/ScreenHandlerType or nil"
  [platform gui-id]
  (get-in @platform-menu-types [platform gui-id]))

(defn unregister-menu-types!
  "Clear all registered menu types (for testing)"
  []
  (reset! platform-menu-types {}))

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate-gui-metadata
  "Validate that all GUI IDs have complete metadata
  
  Returns: vector of error messages (empty if valid)"
  []
  (let [errors (atom [])]
    (doseq [gui-id (get-all-gui-ids)]
      (let [display-name (gui-dsl/get-display-name gui-id)
            gui-type (gui-dsl/get-gui-type gui-id)
            registry-name (gui-dsl/get-registry-name gui-id)
            screen-factory-fn-kw (gui-dsl/get-screen-factory-fn-kw gui-id)]
        (when-not display-name
          (swap! errors conj (str "Missing display name for GUI ID " gui-id)))
        (when-not gui-type
          (swap! errors conj (str "Missing type for GUI ID " gui-id)))
        (when-not registry-name
          (swap! errors conj (str "Missing registry name for GUI ID " gui-id)))
        (when-not screen-factory-fn-kw
          (swap! errors conj (str "Missing screen-factory-fn-kw for GUI ID " gui-id)))))
    
    @errors))

(defn assert-valid-metadata!
  "Assert that all GUI metadata is valid (throws on error)"
  []
  (let [errors (validate-gui-metadata)]
    (when (seq errors)
      (throw (ex-info "Invalid GUI metadata"
                     {:errors errors})))))

;; ============================================================================
;; Design Notes
;; ============================================================================

;; This metadata manager provides:
;;
;; 1. **Centralized Constants**:
;;    - GUI IDs defined once
;;    - Valid GUI ID set for validation
;;
;; 2. **Metadata Maps**:
;;    - Display names: GUI ID → "Wireless Node"
;;    - Types: GUI ID → :node/:matrix
;;    - Registry names: GUI ID → "wireless_node_gui"
;;
;; 3. **Query API**:
;;    - get-display-name: Safe lookup with default
;;    - get-gui-type: Get container type
;;    - get-registry-name: Get registry identifier
;;
;; 4. **Reverse Lookups**:
;;    - type-to-gui-id: :node → 0
;;    - Enables container→GUI ID mapping
;;
;; 5. **Platform Registry**:
;;    - Store platform-specific MenuType instances
;;    - Eliminates hardcoded Java static references
;;    - register-menu-type!, get-menu-type
;;
;; 6. **Validation**:
;;    - validate-gui-metadata: Check completeness
;;    - assert-valid-metadata!: Fail-fast on startup
;;
;; Benefits:
;; - Single source of truth for GUI metadata
;; - No more case statements in bridge code
;; - Easy to add new GUI types
;; - Platform-agnostic metadata with platform-specific storage
;;
;; Usage in platform code:
;;   (defn -getDisplayName [this]
;;     (Text/literal (gui-metadata/get-display-name gui-id)))
;;
;;   (defn -createMenu [this ...]
;;     (let [menu-type (gui-metadata/get-menu-type :forge-1.16.5 gui-id)]
;;       ...))
