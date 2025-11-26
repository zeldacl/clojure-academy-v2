(ns my-mod.inventory.core
  "Inventory system - IInventory protocol and utilities
  
  Provides Clojure protocol equivalent to Minecraft's IInventory interface."
  (:require [my-mod.util.log :as log]))

;; ============================================================================
;; IInventory Protocol
;; ============================================================================

(defprotocol IInventory
  "Protocol for inventory management (equivalent to Minecraft IInventory)"
  
  (get-size-inventory [this]
    "Get the number of slots in this inventory")
  
  (get-stack-in-slot [this slot]
    "Get the ItemStack in the specified slot
    Returns nil if slot is empty")
  
  (decr-stack-size [this slot count]
    "Decrease the stack size by count and return the removed ItemStack
    If count >= stack size, removes entire stack")
  
  (remove-stack-from-slot [this slot]
    "Remove and return the entire ItemStack from slot")
  
  (set-inventory-slot-contents [this slot stack]
    "Set the contents of a slot to the given ItemStack")
  
  (get-inventory-stack-limit [this]
    "Get the maximum stack size for any slot (usually 64)")
  
  (is-usable-by-player? [this player]
    "Check if player can use this inventory")
  
  (is-item-valid-for-slot? [this slot stack]
    "Check if the given ItemStack is valid for the specified slot")
  
  (get-inventory-name [this]
    "Get the inventory name for display")
  
  (has-custom-name? [this]
    "Check if inventory has a custom name"))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn inventory?
  "Check if object implements IInventory protocol"
  [obj]
  (satisfies? IInventory obj))

(defn get-all-stacks
  "Get all ItemStacks from inventory as vector"
  [inventory]
  (vec (for [i (range (get-size-inventory inventory))]
         (get-stack-in-slot inventory i))))

(defn clear-inventory!
  "Clear all slots in inventory"
  [inventory]
  (doseq [i (range (get-size-inventory inventory))]
    (set-inventory-slot-contents inventory i nil)))

(defn count-items
  "Count total number of items in inventory"
  [inventory]
  (reduce + 0
          (for [i (range (get-size-inventory inventory))]
            (if-let [stack (get-stack-in-slot inventory i)]
              (.getCount stack)
              0))))

(defn has-space?
  "Check if inventory has space for an item"
  [inventory item-stack]
  (let [size (get-size-inventory inventory)]
    (some (fn [slot]
            (let [existing (get-stack-in-slot inventory slot)]
              (or (nil? existing)
                  (and (.isItemEqual existing item-stack)
                       (< (.getCount existing) 
                          (min (.getMaxStackSize item-stack)
                               (get-inventory-stack-limit inventory)))))))
          (range size))))

;; ============================================================================
;; NBT Serialization
;; ============================================================================

(defn write-inventory-to-nbt
  "Write inventory contents to NBT
  
  Parameters:
  - inventory: IInventory instance
  - nbt: NBTTagCompound to write to
  - key: NBT key (default: 'inventory')
  
  Returns: NBTTagCompound"
  ([inventory nbt]
   (write-inventory-to-nbt inventory nbt "inventory"))
  ([inventory nbt key]
   (let [inv-tag (net.minecraft.nbt.NBTTagCompound.)
         size (get-size-inventory inventory)]
     (doseq [i (range size)]
       (when-let [stack (get-stack-in-slot inventory i)]
         (when-not (.isEmpty stack)
           (let [stack-tag (net.minecraft.nbt.NBTTagCompound.)]
             (.writeToNBT stack stack-tag)
             (.setTag inv-tag (str i) stack-tag)))))
     (.setTag nbt key inv-tag)
     nbt)))

(defn read-inventory-from-nbt
  "Read inventory contents from NBT
  
  Parameters:
  - inventory: IInventory instance
  - nbt: NBTTagCompound to read from
  - key: NBT key (default: 'inventory')
  
  Side effects: Updates inventory contents"
  ([inventory nbt]
   (read-inventory-from-nbt inventory nbt "inventory"))
  ([inventory nbt key]
   (when (.hasKey nbt key)
     (let [inv-tag (.getCompoundTag nbt key)
           size (get-size-inventory inventory)]
       (doseq [i (range size)]
         (let [slot-key (str i)]
           (if (.hasKey inv-tag slot-key)
             (let [stack-tag (.getCompoundTag inv-tag slot-key)
                   stack (net.minecraft.item.ItemStack. stack-tag)]
               (set-inventory-slot-contents inventory i stack))
             (set-inventory-slot-contents inventory i nil))))))))

;; ============================================================================
;; Default Implementations
;; ============================================================================

(defn default-is-usable-by-player?
  "Default implementation: always usable"
  [inventory player]
  true)

(defn default-is-item-valid-for-slot?
  "Default implementation: all items valid"
  [inventory slot stack]
  true)

(defn default-has-custom-name?
  "Default implementation: no custom name"
  [inventory]
  false)

(defn default-get-inventory-stack-limit
  "Default implementation: 64 items per slot"
  [inventory]
  64)
