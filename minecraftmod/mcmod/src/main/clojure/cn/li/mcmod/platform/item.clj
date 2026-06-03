(ns cn.li.mcmod.platform.item
  "Platform-agnostic ItemStack abstraction layer."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

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

(def ^:private ^:dynamic *item-factory* nil)
(def ^:private ^:dynamic *item-stack-resolver* nil)

(defn install-item-factories!
  [{:keys [item-factory item-stack-resolver] :as factories} label]
  (when item-factory
    (prt/install-impl! #'*item-factory* item-factory (str (or label "item") "-factory")))
  (when item-stack-resolver
    (prt/install-impl! #'*item-stack-resolver* item-stack-resolver (str (or label "item") "-resolver")))
  nil)

(defn call-with-item-factories [factories f]
  (binding [*item-factory* (or (:item-factory factories) *item-factory*)
            *item-stack-resolver* (or (:item-stack-resolver factories) *item-stack-resolver*)]
    (f)))

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

(defn create-item-stack-by-id
  "Create ItemStack from registry id and count using platform resolver.

  Returns nil if resolver is unavailable or item cannot be resolved."
  [item-id count]
  (let [n (int (or count 1))]
    (when (and (string? item-id) (pos? n) *item-stack-resolver*)
      (*item-stack-resolver* item-id n))))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn factory-initialized?
  "Check if the ItemStack factory has been initialized by platform code."
  []
  (some? *item-factory*))

(defn resolver-initialized?
  "Check if item-id resolver has been initialized by platform code."
  []
  (some? *item-stack-resolver*))

;; `item-split` is implemented per-platform via the IItemStack protocol
