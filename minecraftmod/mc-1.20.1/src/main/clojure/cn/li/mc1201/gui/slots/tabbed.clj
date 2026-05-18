(ns cn.li.mc1201.gui.slots.tabbed
  "Shared tab slot synchronization helpers for menu bridges."
  (:import [net.minecraft.world.inventory DataSlot]))

(defn create-tab-data-slot
  [clj-container]
  (doto (DataSlot/standalone)
    (.set (int @(:tab-index clj-container)))))

(defn sync-tab-slot-from-container!
  [^DataSlot tab-slot clj-container]
  (when (and tab-slot clj-container (:tab-index clj-container))
    (.set tab-slot (int @(:tab-index clj-container)))))