(ns cn.li.mcmod.platform.item
  "Platform-agnostic ItemStack abstraction layer.
  
  Supports Forge 1.20.1 and Fabric 1.20.1 modern APIs.
  
  Design:
  - ItemStack objects are passed from platform layer (not created in core)
  - Protocols extended directly to Minecraft's ItemStack classes
  - Core code uses protocol methods instead of direct Java calls
  - Factory function for creating ItemStack from NBT")

;; ============================================================================
;; ItemStack Protocol
;; ============================================================================

(defprotocol IItemStack
  "Protocol for ItemStack operations.
  
  Platform implementations extend this to their ItemStack classes."
  
  (item-is-empty? [this]
    "Check if ItemStack is empty (air/null)")
  
  (item-get-count [this]
    "Get item count in stack. Returns int.")
  
  (item-get-max-stack-size [this]
    "Get maximum stack size for this item. Returns int.")
  
  (item-is-equal? [this other]
    "Check if two ItemStacks are same item (ignoring count). Returns boolean.")
  
  (item-save-to-nbt [this nbt]
    "Write ItemStack to NBT compound. Returns nbt for chaining.")
  
  (item-get-or-create-tag [this]
    "Get NBT tag compound from ItemStack, creating if necessary. Returns INBTCompound.")
  
  (item-get-max-damage [this]
    "Get maximum damage value for this item. Returns int.")
  
  (item-set-damage! [this damage]
    "Set item damage value for durability bar display. Returns nil.")

  (item-get-damage [this]
    "Get current damage value for this item. Returns int.")

  (item-get-item [this]
    "Get the Item type from this ItemStack. Returns Item object.")

  (item-get-tag-compound [this]
    "Get NBT tag compound from ItemStack (may be null). Returns NBT or nil.")

  (item-split [this amount]
    "Split this stack by `amount`, returning the taken stack (platform object)."))

;; ============================================================================
;; Item Protocol (for Item objects, not ItemStack)
;; ============================================================================

(defprotocol IItem
  "Protocol for Item operations (the item type, not the stack)."

  (item-get-description-id [this]
    "Get the translation key/description ID for this item. Returns String.")

  (item-get-registry-name [this]
    "Get the Minecraft registry path/name for this item. Returns String or nil."))

;; ============================================================================
;; Platform Factory Registration
;; ============================================================================

(defonce ^{:dynamic true
           :doc "Platform-specific ItemStack factory function.
                 
                 Must be initialized by platform code before core code runs.
                 
                 Expected signature: (fn [nbt-compound] -> IItemStack)
                 
                 Platform implementations:
                 - Forge 1.20.1: ItemStack.of(nbt)
                 - Fabric 1.20.1: ItemStack.fromNbt(nbt)
                 
                 Example platform initialization:
                 (alter-var-root #'cn.li.mcmod.platform.item/*item-factory*
                   (constantly (fn [nbt] (ItemStack/of nbt))))"}
  *item-factory*
  nil)

;; ============================================================================
;; Factory Functions
;; ============================================================================

(defn create-item-from-nbt
  "Create ItemStack from NBT compound.
  
  Args:
  - nbt: INBTCompound containing ItemStack data
  
  Returns: IItemStack implementation from current platform
  Throws: ex-info if platform not initialized"
  [nbt]
  (if-let [factory *item-factory*]
    (factory nbt)
    (throw (ex-info "ItemStack factory not initialized - platform must call init-platform! first"
                    {:hint "Check that platform mod initialization calls platform-impl/init-platform!"}))))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn factory-initialized?
  "Check if the ItemStack factory has been initialized by platform code."
  []
  (some? *item-factory*))

;; `item-split` is implemented per-platform via the IItemStack protocol
