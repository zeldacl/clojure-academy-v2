(ns cn.li.mc262.gui.menu.container
  "Minecraft 26.2 container adapter for Clojure-side containers.

  Wraps a Clojure container map (via mcmod gui.adapter.platform-registry) as a
  vanilla `net.minecraft.world.Container` for loader slot machinery."
  (:require [cn.li.mcmod.gui.adapter.platform-registry :as platform])
  (:import [cn.li.mc262.shim UniversalContainer]))

(defn create-tile-inventory-adapter
  "Return a `Container` Java skeleton backed by a Clojure container.

  The loader-facing menu bridge delegates all slot operations through this adapter."
  [clj-container]
  (UniversalContainer.
    clj-container
    (fn [c] (int (or (platform/slot-count c) 0)))
    (fn [c slot] (or (platform/slot-get-item c (int slot)) nil))
    (fn [c slot stack] (platform/slot-set-item! c (int slot) stack))
    (fn [c slot] (platform/slot-changed! c (int slot)))
    (fn [c player] (boolean (platform/safe-validate c player)))
    (fn [c slot stack] (boolean (platform/slot-can-place? c (int slot) stack)))))
