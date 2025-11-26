(ns my-mod.registry.metadata
  "Registry metadata system - single source of truth for block/item registration.
  
  This module provides metadata-driven registration, ensuring platform code
  does not contain hardcoded game-specific block/item names. All registration
  information is dynamically retrieved from the DSL systems.
  
  Architecture:
  - Platform code queries this module for what to register
  - This module queries DSL systems for available blocks/items
  - Game logic remains in core, platform code remains generic"
  (:require [my-mod.block.dsl :as bdsl]
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

(defn get-block-registry-name
  "Returns the Minecraft registry name for a block ID.
  
  Converts DSL block ID (kebab-case) to Minecraft registry format (snake_case).
  
  Args:
    block-id: String - DSL block identifier (e.g., \"demo-block\")
  
  Returns:
    String - Registry name (e.g., \"demo_block\")"
  [block-id]
  (clojure.string/replace block-id #"-" "_"))

(defn get-block-spec
  "Retrieves the full block specification from the DSL.
  
  Args:
    block-id: String - DSL block identifier
  
  Returns:
    Map - Block specification with all properties"
  [block-id]
  (bdsl/get-block block-id))

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

;; Initialization
;; ============================================================

(defn init-registry-metadata!
  "Initialize registry metadata system.
  
  Called during mod initialization to ensure DSL systems are ready.
  Platform code should call this before attempting registration."
  []
  ;; DSL systems are already initialized by block-demo/item-demo
  ;; This function exists for future initialization needs
  nil)
