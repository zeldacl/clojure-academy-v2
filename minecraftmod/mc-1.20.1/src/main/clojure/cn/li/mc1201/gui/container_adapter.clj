(ns cn.li.mc1201.gui.container-adapter
  "Shared Minecraft 1.20.1 Container adapter for Clojure-side containers.

  Wraps a Clojure container map (using mcmod gui.adapter protocol) as a
  vanilla `net.minecraft.world.Container` so Forge and Fabric slot machinery
  can interact with it without platform-specific code."
  (:require [cn.li.mcmod.gui.adapter :as gui])
  (:import [net.minecraft.world Container]
           [net.minecraft.world.item ItemStack]))

(defn create-tile-inventory-adapter
  "Return a `Container` reify backed by a Clojure container.

  Works identically on Forge and Fabric – both use the same MC 1.20.1 API."
  [clj-container]
  (reify Container
    (getContainerSize [_]
      (int (or (gui/slot-count clj-container) 0)))

    (isEmpty [_]
      (let [n (int (or (gui/slot-count clj-container) 0))]
        (not-any? (fn [idx]
                    (let [stack (gui/slot-get-item clj-container idx)]
                      (and stack (not (.isEmpty ^ItemStack stack)))))
                  (range n))))

    (getItem [_ slot]
      (or (gui/slot-get-item clj-container (int slot))
          ItemStack/EMPTY))

    (removeItem [_ slot amount]
      (let [slot   (int slot)
            amount (int amount)
            current (or (gui/slot-get-item clj-container slot)
                        ItemStack/EMPTY)]
        (if (or (nil? current) (.isEmpty ^ItemStack current) (<= amount 0))
          ItemStack/EMPTY
          (let [taken (.split ^ItemStack current amount)]
            (if (.isEmpty ^ItemStack current)
              (gui/slot-set-item! clj-container slot nil)
              (gui/slot-set-item! clj-container slot current))
            (gui/slot-changed! clj-container slot)
            taken))))

    (removeItemNoUpdate [_ slot]
      (let [slot    (int slot)
            current (or (gui/slot-get-item clj-container slot)
                        ItemStack/EMPTY)]
        (gui/slot-set-item! clj-container slot nil)
        (gui/slot-changed! clj-container slot)
        current))

    (setItem [_ slot stack]
      (gui/slot-set-item! clj-container (int slot) stack)
      (gui/slot-changed! clj-container (int slot)))

    (setChanged [_]
      (let [n (int (or (gui/slot-count clj-container) 0))]
        (doseq [idx (range n)]
          (gui/slot-changed! clj-container idx))))

    (stillValid [_ player]
      (boolean (gui/safe-validate clj-container player)))

    (canPlaceItem [_ slot stack]
      (boolean (gui/slot-can-place? clj-container (int slot) stack)))

    (clearContent [_]
      (let [n (int (or (gui/slot-count clj-container) 0))]
        (doseq [idx (range n)]
          (gui/slot-set-item! clj-container idx nil))
        (doseq [idx (range n)]
          (gui/slot-changed! clj-container idx))))))
