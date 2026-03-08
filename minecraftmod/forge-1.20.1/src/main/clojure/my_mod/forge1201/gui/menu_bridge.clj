(ns my-mod.forge1201.gui.menu-bridge
  "Forge 1.20.1 AbstractContainerMenu wrapper.

  Uses runtime `proxy` instead of `gen-class` because gen-class is a
  compile-time-only macro (guarded by *compile-files*) and produces no class
  when Clojure source files are loaded dynamically without AOT compilation."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.util.log :as log])
  (:import [net.minecraft.world.inventory AbstractContainerMenu MenuType Slot]
           [net.minecraft.world.item ItemStack]))

(defn create-menu-bridge
  "Create an AbstractContainerMenu proxy wrapping a Clojure container.

  proxy generates the implementing class at runtime inside the current
  DynamicClassLoader, so it works without AOT compilation.

  Args:
  - window-id:     int        — Forge menu container id
  - menu-type:     MenuType   — registered MenuType for this GUI
  - clj-container: map        — Clojure-side container (NodeContainer, etc.)"
  [window-id menu-type clj-container]
  (proxy [AbstractContainerMenu] [menu-type (int window-id)]

    (stillValid [player]
      (gui/safe-validate clj-container player))

    (removed [player]
      (proxy-super removed player)
      (gui/safe-close! clj-container)
      (gui/unregister-active-container! clj-container)
      (gui/unregister-player-container! player)
      (log/info "Menu closed for player" (.getName player)))

    (broadcastChanges []
      (proxy-super broadcastChanges)
      (gui/safe-sync! clj-container))

    (quickMoveStack [player slot-index]
      (try
        (let [slot (.getSlot this slot-index)]
          (if (and slot (.hasItem slot))
            (let [stack (.getItem slot)]
              (gui/execute-quick-move-forge this clj-container slot-index slot stack))
            ItemStack/EMPTY))
        (catch Exception e
          (log/error "Error in quickMoveStack:" (.getMessage e))
          ItemStack/EMPTY)))

    (canTakeItemForPickAll [stack slot] true)
    (canDragTo [slot] true)))
