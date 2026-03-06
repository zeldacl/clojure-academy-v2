(ns my-mod.registry.metadata
  "Registry metadata system - single source of truth for block/item registration.
  
  This module provides metadata-driven registration, ensuring platform code
  does not contain hardcoded game-specific block/item names. All registration
  information is dynamically retrieved from the DSL systems.
  
  Architecture:
  - Platform code queries this module for what to register
  - This module queries DSL systems for available blocks/items
  - Game logic remains in core, platform code remains generic"
  (:require [clojure.string :as str]
            [my-mod.block.dsl :as bdsl]
            [my-mod.item.dsl :as idsl]))

;; Block Registration Metadata
;; ============================================================

(defn get-all-block-ids
  "Returns a sequence of all registered block IDs from the block DSL.
  
  Platform code should iterate over this list to register all blocks,
  without knowing specific block names.
  
  Returns:
    Sequence of block ID strings (e.g., [\"demo-block\" \"copper-ore\" ...])"
  []
  (bdsl/list-blocks))

(declare get-block-spec)

(defn get-block-registry-name
  "Returns the Minecraft registry name for a block ID.
  
  Uses explicit :registry-name override from block spec when provided,
  otherwise converts DSL block ID (kebab-case) to Minecraft registry format
  (snake_case).
  
  Args:
    block-id: String - DSL block identifier (e.g., \"demo-block\")
  
  Returns:
    String - Registry name (e.g., \"demo_block\")"
  [block-id]
  (let [block-spec (get-block-spec block-id)
        explicit-name (:registry-name block-spec)]
    (if (and (string? explicit-name) (not (str/blank? explicit-name)))
      explicit-name
      (str/replace block-id #"-" "_"))))

(defn get-block-spec
  "Retrieves the full block specification from the DSL.
  
  Args:
    block-id: String - DSL block identifier
  
  Returns:
    Map - Block specification with all properties"
  [block-id]
  (bdsl/get-block block-id))

(defn has-block-state-properties?
  "Checks if a block has dynamic block state properties.
  
  Args:
    block-id: String - DSL block identifier
  
  Returns:
    Boolean - true if block has block-state-properties defined"
  [block-id]
  (let [block-spec (get-block-spec block-id)]
    (some? (:block-state-properties block-spec))))

(defn get-block-state-properties
  "Returns the block state properties definition for a block.
  
  Args:
    block-id: String - DSL block identifier
  
  Returns:
    Map - Block state properties (e.g., {:energy {...} :connected {...}}), or nil"
  [block-id]
  (let [block-spec (get-block-spec block-id)]
    (:block-state-properties block-spec)))

;; Item Registration Metadata
;; ============================================================

(defn get-all-item-ids
  "Returns a sequence of all registered item IDs from the item DSL.
  
  Platform code should iterate over this list to register all items,
  without knowing specific item names.
  
  Returns:
    Sequence of item ID strings (e.g., [\"demo-item\" \"copper-ingot\" ...])"
  []
  (idsl/list-items))

(defn get-item-registry-name
  "Returns the Minecraft registry name for an item ID.
  
  Converts DSL item ID (kebab-case) to Minecraft registry format (snake_case).
  
  Args:
    item-id: String - DSL item identifier (e.g., \"demo-item\")
  
  Returns:
    String - Registry name (e.g., \"demo_item\")"
  [item-id]
  (clojure.string/replace item-id #"-" "_"))

(defn get-item-spec
  "Retrieves the full item specification from the DSL.
  
  Args:
    item-id: String - DSL item identifier
  
  Returns:
    Map - Item specification with all properties"
  [item-id]
  (idsl/get-item item-id))

;; BlockItem Registration Metadata
;; ============================================================

(defn should-create-block-item?
  "Determines if a block should have a corresponding BlockItem.
  
  By default, all blocks get BlockItems for placement in the world.
  This can be customized based on block properties if needed.
  
  Args:
    block-id: String - DSL block identifier
  
  Returns:
    Boolean - true if BlockItem should be created"
  [block-id]
  ;; Default: all blocks get BlockItems
  ;; Future: could check block spec for :has-item-form property
  true)

;; Creative Tab Metadata
;; ============================================================

(defn get-item-creative-tab
  "Returns the creative tab for an item.
  
  Args:
    item-id: String - DSL item identifier
  
  Returns:
    Keyword - Creative tab (e.g., :misc, :tools, :combat)"
  [item-id]
  (let [item-spec (get-item-spec item-id)]
    (or (:creative-tab item-spec) :misc)))

(defn get-block-creative-tab
  "Returns the creative tab for a block (used for block items).
  
  Args:
    block-id: String - DSL block identifier
  
  Returns:
    Keyword - Creative tab (e.g., :misc, :building-blocks)"
  [block-id]
  (let [block-spec (get-block-spec block-id)]
    (or (:creative-tab block-spec) :misc)))

(defn get-all-creative-tab-entries
  "Returns all items and block-items that should be added to creative tabs.
  
  Platform code should use this to populate creative mode tabs without
  knowing specific item/block names.
  
  Returns:
    Sequence of maps with:
      :type - :item or :block-item
      :id - registry ID (string)
      :tab - creative tab keyword
      :registry-name - Minecraft registry name
  
  Example:
    [{:type :item :id \"demo-item\" :tab :misc :registry-name \"demo_item\"}
     {:type :block-item :id \"demo-block\" :tab :building-blocks :registry-name \"demo_block\"}]"
  []
  (let [;; Get all standalone items
        item-entries (for [item-id (get-all-item-ids)]
                       {:type :item
                        :id item-id
                        :tab (get-item-creative-tab item-id)
                        :registry-name (get-item-registry-name item-id)})
        
        ;; Get all block items
        block-item-entries (for [block-id (get-all-block-ids)
                                 :when (should-create-block-item? block-id)]
                             {:type :block-item
                              :id block-id
                              :tab (get-block-creative-tab block-id)
                              :registry-name (get-block-registry-name block-id)})]
    
    ;; Combine and return all entries
    (concat item-entries block-item-entries)))

;; Initialization
;; ============================================================

(defn init-registry-metadata!
  "Initialize registry metadata system.
  
  Called during mod initialization to ensure DSL systems are ready.
  Platform code should call this before attempting registration."
  []
  ;; DSL systems are initialized when their namespaces are loaded
  ;; This function exists for future initialization needs
  nil)
