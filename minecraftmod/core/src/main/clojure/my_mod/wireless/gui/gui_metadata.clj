(ns my-mod.wireless.gui.gui-metadata
  "Centralized GUI metadata management
  
  This namespace contains all GUI-related constants and mappings,
  eliminating hardcoded case statements in platform-specific code."
  (:require [my-mod.util.log :as log]))

;; ============================================================================
;; GUI ID Constants
;; ============================================================================

(def gui-wireless-node 0)
(def gui-wireless-matrix 1)
(def gui-solar-gen 2)

;; All valid GUI IDs
(def valid-gui-ids #{gui-wireless-node gui-wireless-matrix gui-solar-gen})

;; ============================================================================
;; GUI Metadata Maps
;; ============================================================================

(def gui-display-names
  ^{:doc "Map from GUI ID to display name"}
  {gui-wireless-node "Wireless Node"
   gui-wireless-matrix "Wireless Matrix"
   gui-solar-gen "Solar Generator"})

(def gui-types
  ^{:doc "Map from GUI ID to container type keyword"}
  {gui-wireless-node :node
   gui-wireless-matrix :matrix
   gui-solar-gen :solar})

(def gui-registry-names
  ^{:doc "Map from GUI ID to registry identifier"}
  {gui-wireless-node "wireless_node_gui"
   gui-wireless-matrix "wireless_matrix_gui"
   gui-solar-gen "solar_gen_gui"})

;; Screen factory function mapping
(def gui-screen-factories
  ^{:doc "Map from GUI ID to screen factory function keyword

  Platform code can use this to get the correct factory function
  from screen-factory namespace dynamically."}
  {gui-wireless-node :create-node-screen
   gui-wireless-matrix :create-matrix-screen
   gui-solar-gen :create-solar-screen})

;; Slot layout definitions
(def gui-slot-layouts
  ^{:doc "Map from GUI ID to slot layout configuration

  Each layout contains:
  - :slots - Vector of slot definitions with {:type :index :x :y}
  - :ranges - Map of section ranges {:tile [start end] :player-main [...] :player-hotbar [...]}"}
  {gui-wireless-node
   {:slots [{:type :energy :index 0 :x 0 :y 0}
            {:type :output :index 1 :x 26 :y 0}]
    :ranges {:tile [0 1]
             :player-main [2 28]
             :player-hotbar [29 37]}}
   
   gui-wireless-matrix
   {:slots [{:type :plate :index 0 :x 0 :y 0}
            {:type :plate :index 1 :x 34 :y 0}
            {:type :plate :index 2 :x 68 :y 0}
            {:type :core :index 3 :x 47 :y 24}]
    :ranges {:tile [0 3]
             :player-main [4 30]
             :player-hotbar [31 39]}}

   gui-solar-gen
   {:slots [{:type :energy :index 0 :x 42 :y 81}]
    :ranges {:tile [0 0]
             :player-main [1 27]
             :player-hotbar [28 36]}}})

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
  (if (contains? valid-gui-ids id)
    (do
      (log/warn "register-gui!: GUI ID is already present in valid-gui-ids, skipping seed of base tables"
                {:gui-key gui-key :id id})
      ;; Even if ID exists, still extend type->gui-id so new gui-key can map to same id.
      (alter-var-root #'type-to-gui-id assoc type id)
      nil)
    (do
      (log/info "Registering GUI metadata" {:gui-key gui-key :id id})
      ;; Extend ID set and all metadata maps.
      (alter-var-root #'valid-gui-ids conj id)
      (alter-var-root #'gui-display-names assoc id display-name)
      (alter-var-root #'gui-types assoc id type)
      (alter-var-root #'gui-registry-names assoc id registry-name)
      (alter-var-root #'gui-screen-factories assoc id screen-fn-kw)
      (alter-var-root #'gui-slot-layouts assoc id slot-layout)
      (alter-var-root #'type-to-gui-id assoc type id)
      nil)))

;; ============================================================================
;; Query Functions
;; ============================================================================

(defn get-all-gui-ids
  "Get all registered GUI IDs
  
  Returns: seq of int"
  []
  (seq valid-gui-ids))

(defn valid-gui-id?
  "Check if GUI ID is valid
  
  Args:
  - gui-id: int
  
  Returns: boolean"
  [gui-id]
  (contains? valid-gui-ids gui-id))

(defn get-display-name
  "Get display name for GUI ID
  
  Args:
  - gui-id: int
  
  Returns: string or \"Unknown GUI\" if invalid"
  [gui-id]
  (get gui-display-names gui-id "Unknown GUI"))

(defn get-gui-type
  "Get container type for GUI ID
  
  Args:
  - gui-id: int
  
  Returns: :node, :matrix, or :unknown"
  [gui-id]
  (get gui-types gui-id :unknown))

(defn get-registry-name
  "Get registry identifier for GUI
  
  Args:
  - gui-id: int
  
  Returns: string (registry name) or \"unknown_gui\""
  [gui-id]
  (get gui-registry-names gui-id "unknown_gui"))

(defn get-screen-factory-fn
  "Get screen factory function keyword for GUI
  
  Args:
  - gui-id: int
  
  Returns: keyword (:create-node-screen, :create-matrix-screen, etc.) or nil"
  [gui-id]
  (get gui-screen-factories gui-id))

(defn get-slot-layout
  "Get slot layout configuration for GUI
  
  Args:
  - gui-id: int
  
  Returns: map with {:slots [...] :ranges {...}} or nil"
  [gui-id]
  (get gui-slot-layouts gui-id))

(defn get-slot-range
  "Get slot index range for a GUI section
  
  Args:
  - gui-id: int
  - section: :tile, :player-main, :player-hotbar
  
  Returns: [start-index end-index] (inclusive) or [0 0] if not found"
  [gui-id section]
  (if-let [layout (get-slot-layout gui-id)]
    (get-in layout [:ranges section] [0 0])
    [0 0]))

;; ============================================================================
;; Reverse Lookups
;; ============================================================================

;; ============================================================================
;; Reverse Lookups
;; ============================================================================

(def type-to-gui-id
  ^{:doc "Map from container type to GUI ID"}
  {:node gui-wireless-node
   :matrix gui-wireless-matrix
   :solar gui-solar-gen})

(defn get-gui-id-for-type
  "Get GUI ID for container type
  
  Args:
  - container-type: :node or :matrix
  
  Returns: int or nil if unknown"
  [container-type]
  (get type-to-gui-id container-type))

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
    (doseq [gui-id valid-gui-ids]
      (when-not (get gui-display-names gui-id)
        (swap! errors conj (str "Missing display name for GUI ID " gui-id)))
      
      (when-not (get gui-types gui-id)
        (swap! errors conj (str "Missing type for GUI ID " gui-id)))
      
      (when-not (get gui-registry-names gui-id)
        (swap! errors conj (str "Missing registry name for GUI ID " gui-id))))
    
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
