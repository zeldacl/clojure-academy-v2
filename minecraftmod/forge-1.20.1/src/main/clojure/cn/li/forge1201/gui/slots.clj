(ns cn.li.forge1201.gui.slots
  "Forge 1.20.1 GUI Slot Implementations

  Uses runtime `proxy` instead of `gen-class` for each custom slot type.
  gen-class is a compile-time-only macro (guarded by *compile-files*) and
  produces no class when Clojure source files are loaded without AOT."
  (:require [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mc1201.gui.slots-common :as slots-common])
  (:import [cn.li.mc1201.gui CMenuBridge]
           [net.minecraft.world.inventory Slot AbstractContainerMenu]
           [net.minecraft.world.entity.player Inventory]))

(defn- add-slot!
  [^CMenuBridge container ^Slot slot]
  (.addSlotPublic container slot))

;; ============================================================================
;; Slot Factory Functions
;; ============================================================================

(defn create-energy-slot
  "Create a slot that only accepts energy items."
  [inventory slot-index x y]
  (slots-common/create-energy-slot inventory slot-index x y))

(defn create-plate-slot
  "Create a slot that filters for constraint-plate items."
  [inventory slot-index x y]
  (slots-common/create-plate-slot inventory slot-index x y))

(defn create-core-slot
  "Create a slot that filters for mat-core items."
  [inventory slot-index x y]
  (slots-common/create-core-slot inventory slot-index x y))

(defn create-output-slot
  "Create an output-only slot (no insertion allowed)."
  [inventory slot-index x y]
  (slots-common/create-output-slot inventory slot-index x y))

 (defn create-standard-slot
   "Create a standard slot (accepts any item)."
   [inventory slot-index x y]
  (slots-common/create-standard-slot inventory slot-index x y))

;; ============================================================================
;; Conditional slots (tabbed GUI: active only when active?-fn returns true)
;; ============================================================================

(defn create-conditional-slot
  "Create a Slot that only allows placement/pickup when active?-fn returns true.
   Used for tabbed GUIs: slots active only on inv-window tab (tab-index 0).
   active?-fn: (fn [] boolean)"
  [inventory slot-index x y active?-fn]
  (slots-common/create-conditional-slot inventory slot-index x y active?-fn))

(defn create-conditional-energy-slot
  [inventory slot-index x y active?-fn]
  (slots-common/create-conditional-energy-slot inventory slot-index x y active?-fn))

(defn create-conditional-plate-slot
  [inventory slot-index x y active?-fn]
  (slots-common/create-conditional-plate-slot inventory slot-index x y active?-fn))

(defn create-conditional-core-slot
  [inventory slot-index x y active?-fn]
  (slots-common/create-conditional-core-slot inventory slot-index x y active?-fn))

(defn create-conditional-output-slot
  [inventory slot-index x y active?-fn]
  (slots-common/create-conditional-output-slot inventory slot-index x y active?-fn))

(defn- slot-by-type-conditional
  [type inventory index abs-x abs-y active?-fn]
  (case type
    :energy (create-conditional-energy-slot  inventory index abs-x abs-y active?-fn)
    :plate  (create-conditional-plate-slot   inventory index abs-x abs-y active?-fn)
    :core   (create-conditional-core-slot    inventory index abs-x abs-y active?-fn)
    :output (create-conditional-output-slot   inventory index abs-x abs-y active?-fn)
    (create-conditional-slot                 inventory index abs-x abs-y active?-fn)))

;; ============================================================================
;; Slot Layout Helpers
;; ============================================================================

(defn add-player-inventory-slots
  "Add standard player inventory slots (3x9 grid + hotbar).
   When active?-fn is provided (tabbed GUI), slots are conditional on it.

  Args:
  - container:        AbstractContainerMenu to add slots to
  - player-inventory: Inventory
  - x-offset:         int (left edge x position)
  - y-offset:         int (top edge y position for main inventory)
  - active?-fn:       optional (fn [] boolean) — when false, slot interaction disabled"
  ([container player-inventory x-offset y-offset]
   (add-player-inventory-slots container player-inventory x-offset y-offset nil))
  ([^AbstractContainerMenu container ^Inventory player-inventory x-offset y-offset active?-fn]
   (slots-common/add-player-inventory-slots!
     (fn [^Slot s] (add-slot! ^CMenuBridge container s))
     player-inventory
     x-offset
     y-offset
     active?-fn)))

(defn add-player-hotbar-slots
  "Add only player hotbar slots (1x9).
   When active?-fn is provided (tabbed GUI), slots are conditional on it.

  Args:
  - container:        AbstractContainerMenu to add slots to
  - player-inventory: Inventory
  - x-offset:         int (left edge x position)
  - y-offset:         int (top edge y position for main inventory; hotbar is at +58)
  - active?-fn:       optional (fn [] boolean)"
  ([container player-inventory x-offset y-offset]
   (add-player-hotbar-slots container player-inventory x-offset y-offset nil))
  ([^AbstractContainerMenu container ^Inventory player-inventory x-offset y-offset active?-fn]
   (slots-common/add-player-hotbar-slots!
     (fn [^Slot s] (add-slot! ^CMenuBridge container s))
     player-inventory
     x-offset
     y-offset
     active?-fn)))

(defn add-gui-slots
  "Add GUI-specific slots based on metadata-driven layout.
   When active?-fn is provided (tabbed GUI), slots are conditional on it.

  Args:
  - container:  AbstractContainerMenu to add slots to
  - inventory:  Container for the GUI
  - gui-id:     int (GUI identifier)
  - x-offset:   int (base x position)
  - y-offset:   int (base y position)
  - active?-fn: optional (fn [] boolean) — when false, slot interaction disabled"
  ([container inventory gui-id x-offset y-offset]
   (add-gui-slots container inventory gui-id x-offset y-offset nil))
  ([^AbstractContainerMenu container inventory gui-id x-offset y-offset active?-fn]
   (slots-common/add-gui-slots!
     (fn [^Slot s] (add-slot! ^CMenuBridge container s))
     gui/get-slot-layout
     inventory
     gui-id
     x-offset
     y-offset
     active?-fn)))

;; ============================================================================
;; Slot Index Helpers
;; ============================================================================

(defn get-slot-range
  "Get slot index range for an inventory section.

  Args:
  - gui-id:   int (GUI identifier)
  - section:  :tile, :player-main, or :player-hotbar

  Returns: [start-index end-index] (inclusive)"
  [gui-id section]
  (gui/get-slot-range gui-id section))

(defn slot-in-range?
  "Check if slot index falls within a given inventory section.

  Args:
  - slot-index: int
  - gui-id:     int (GUI identifier)
  - section:    :tile, :player-main, or :player-hotbar

  Returns: boolean"
  [slot-index gui-id section]
  (slots-common/slot-in-range? get-slot-range slot-index gui-id section))

;; ============================================================================
;; Debugging Helpers
;; ============================================================================

(defn log-slot-contents
  "Log all slot contents for debugging."
  [container]
  (slots-common/log-slot-contents container))

(defn validate-slot-setup
  "Validate that container has the expected number of slots.

  Args:
  - container:      AbstractContainerMenu
  - expected-count: int

  Returns: boolean"
  [container expected-count]
  (slots-common/validate-slot-setup container expected-count))
