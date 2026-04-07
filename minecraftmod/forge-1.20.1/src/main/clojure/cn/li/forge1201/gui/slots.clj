(ns cn.li.forge1201.gui.slots
  "Forge 1.20.1 GUI Slot Implementations

  Uses runtime `proxy` instead of `gen-class` for each custom slot type.
  gen-class is a compile-time-only macro (guarded by *compile-files*) and
  produces no class when Clojure source files are loaded without AOT."
  (:require [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.gui CMenuBridge]
           [net.minecraft.world.inventory Slot AbstractContainerMenu]
           [net.minecraft.world.entity.player Inventory]
           [net.minecraft.world.item ItemStack]))

(defn- add-slot!
  [^CMenuBridge container ^Slot slot]
  (.addSlotPublic container slot))

;; ============================================================================
;; Slot Factory Functions
;; ============================================================================

 (defn create-energy-slot
   "Create a slot that only accepts energy items."
   [inventory slot-index x y]
   (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
     (mayPlace [^ItemStack stack]
       (let [pred (slot-registry/get-slot-validator :energy)]
         (and pred (pred stack))))
     (getMaxStackSize [& _] 1)))

 (defn create-plate-slot
   "Create a slot that filters for constraint-plate items."
   [inventory slot-index x y]
   (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
     (mayPlace [^ItemStack stack]
       (let [pred (slot-registry/get-slot-validator :plate)]
         (and pred (pred stack))))
     (getMaxStackSize [& _] 1)))

 (defn create-core-slot
   "Create a slot that filters for mat-core items."
   [inventory slot-index x y]
   (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
     (mayPlace [^ItemStack stack]
       (let [pred (slot-registry/get-slot-validator :core)]
         (and pred (pred stack))))
     (getMaxStackSize [& _] 1)))

 (defn create-output-slot
   "Create an output-only slot (no insertion allowed)."
   [inventory slot-index x y]
   (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
     (mayPlace [_stack] false)
     (mayPickup [_player] true)))

 (defn create-standard-slot
   "Create a standard slot (accepts any item)."
   [inventory slot-index x y]
   (Slot. inventory (int slot-index) (int x) (int y)))

;; ============================================================================
;; Conditional slots (tabbed GUI: active only when active?-fn returns true)
;; ============================================================================

(defn create-conditional-slot
  "Create a Slot that only allows placement/pickup when active?-fn returns true.
   Used for tabbed GUIs: slots active only on inv-window tab (tab-index 0).
   active?-fn: (fn [] boolean)"
  [inventory slot-index x y active?-fn]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [stack]
      (boolean (active?-fn)))
    (mayPickup [_player]
      (boolean (active?-fn)))))

(defn create-conditional-energy-slot
  [inventory slot-index x y active?-fn]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [stack]
      (let [pred (slot-registry/get-slot-validator :energy)]
        (and (active?-fn) pred (pred stack))))
    (mayPickup [_player] (boolean (active?-fn)))
    (getMaxStackSize [& _] 1)))

(defn create-conditional-plate-slot
  [inventory slot-index x y active?-fn]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [stack]
      (let [pred (slot-registry/get-slot-validator :plate)]
        (and (active?-fn) pred (pred stack))))
    (mayPickup [_player] (boolean (active?-fn)))
    (getMaxStackSize [& _] 1)))

(defn create-conditional-core-slot
  [inventory slot-index x y active?-fn]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [stack]
      (let [pred (slot-registry/get-slot-validator :core)]
        (and (active?-fn) pred (pred stack))))
    (mayPickup [_player] (boolean (active?-fn)))
    (getMaxStackSize [& _] 1)))

(defn create-conditional-output-slot
  [inventory slot-index x y active?-fn]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [_stack] (and (active?-fn) false))
    (mayPickup [_player] (boolean (active?-fn)))))

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
   (let [slot-fn (if active?-fn
                   (fn [inv idx x y] (create-conditional-slot inv idx x y active?-fn))
                   (fn [inv idx x y] (create-standard-slot inv idx x y)))]
     (doseq [row (range 3)
             col (range 9)]
       (let [slot-index (+ (* row 9) col 9)
             x (+ x-offset (* col 18))
             y (+ y-offset (* row 18))
             ^Slot s (slot-fn player-inventory slot-index x y)]
         (add-slot! ^CMenuBridge container s)))
     (doseq [col (range 9)]
       (let [slot-index col
             x (+ x-offset (* col 18))
             y (+ y-offset 58)
             ^Slot s (slot-fn player-inventory slot-index x y)]
         (add-slot! ^CMenuBridge container s))))))

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
   (when-let [layout (gui/get-slot-layout gui-id)]
     (doseq [slot-def (:slots layout)]
       (let [{:keys [type index x y]} slot-def
             abs-x (+ x-offset x)
             abs-y (+ y-offset y)
             ^Slot slot (if active?-fn
                          (slot-by-type-conditional (or type :standard) inventory index abs-x abs-y active?-fn)
                          (case type
                            :energy (create-energy-slot  inventory index abs-x abs-y)
                            :plate  (create-plate-slot   inventory index abs-x abs-y)
                            :core   (create-core-slot    inventory index abs-x abs-y)
                            :output (create-output-slot  inventory index abs-x abs-y)
                            (create-standard-slot        inventory index abs-x abs-y)))]
         (add-slot! ^CMenuBridge container slot))))))

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
  (let [[start end] (get-slot-range gui-id section)]
    (<= start slot-index end)))

;; ============================================================================
;; Debugging Helpers
;; ============================================================================

(defn log-slot-contents
  "Log all slot contents for debugging."
  [container]
  (log/debug "Container slots:")
  (let [n (.size (.getItems ^AbstractContainerMenu container))]
    (doseq [i (range n)]
      (let [^Slot slot (.getSlot ^AbstractContainerMenu container i)
            ^ItemStack stack (.getItem slot)]
        (when-not (.isEmpty stack)
          (log/debug "  Slot" i ":"
                     (.getCount stack) "x"
                     (-> stack .getItem .toString)))))))

(defn validate-slot-setup
  "Validate that container has the expected number of slots.

  Args:
  - container:      AbstractContainerMenu
  - expected-count: int

  Returns: boolean"
  [container expected-count]
  (let [actual-count (.size (.getItems ^AbstractContainerMenu container))]
    (if (= actual-count expected-count)
      (do (log/info "Slot validation passed:" actual-count "slots") true)
      (do (log/error "Slot validation failed: expected" expected-count
                     "but got" actual-count) false))))
