(ns cn.li.fabric1201.gui.slots
  "Fabric 1.20.1 GUI slot helpers, backed by shared mc1201 slot logic."
  (:require [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mc1201.gui.slots-common :as slots-common])
  (:import [cn.li.mc1201.gui CMenuBridge]
           [net.minecraft.world.inventory Slot AbstractContainerMenu]
           [net.minecraft.world.entity.player Inventory]))

(defn- add-slot!
  [^CMenuBridge container ^Slot slot]
  (.addSlotPublic container slot))

(defn create-energy-slot [inventory slot-index x y]
  (slots-common/create-energy-slot inventory slot-index x y))

(defn create-plate-slot [inventory slot-index x y]
  (slots-common/create-plate-slot inventory slot-index x y))

(defn create-core-slot [inventory slot-index x y]
  (slots-common/create-core-slot inventory slot-index x y))

(defn create-output-slot [inventory slot-index x y]
  (slots-common/create-output-slot inventory slot-index x y))

(defn create-standard-slot [inventory slot-index x y]
  (slots-common/create-standard-slot inventory slot-index x y))

(defn add-gui-slots
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

(defn get-gui-slot-ranges
  [gui-id]
  {:tile (gui/get-slot-range gui-id :tile)
   :player-main (gui/get-slot-range gui-id :player-main)
   :player-hotbar (gui/get-slot-range gui-id :player-hotbar)})

(defn add-player-inventory-slots
  ([container player-inventory x-offset y-offset]
   (add-player-inventory-slots container player-inventory x-offset y-offset nil))
  ([^AbstractContainerMenu container ^Inventory player-inventory x-offset y-offset active?-fn]
   (slots-common/add-player-inventory-slots!
     (fn [^Slot s] (add-slot! ^CMenuBridge container s))
     player-inventory
     x-offset
     y-offset
     active?-fn)))

(defn get-slot-range
  [gui-id section]
  (gui/get-slot-range gui-id section))

(defn slot-in-range?
  [slot-index gui-id section]
  (slots-common/slot-in-range? get-slot-range slot-index gui-id section))

(defn log-slot-contents
  [container]
  (slots-common/log-slot-contents container))

(defn validate-slot-setup
  [container expected-count]
  (slots-common/validate-slot-setup container expected-count))
