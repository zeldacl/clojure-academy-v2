(ns cn.li.forge1201.gui.menu-bridge
  "Forge 1.20.1 AbstractContainerMenu wrapper.

  Uses runtime `proxy` instead of `gen-class` because gen-class is a
  compile-time-only macro (guarded by *compile-files*) and produces no class
  when Clojure source files are loaded dynamically without AOT compilation.
  Tabbed GUIs: same UI, same container; :tab-index is only the 'current tab' state.
  When container has :tab-index, we add DataSlot + conditional slots (slots active only when tab-index is 0)."
  (:require [cn.li.mcmod.gui.registry-core :as gui]
            [cn.li.mc1201.gui.menu-bridge-core :as menu-core])
  (:import [cn.li.mc1201.gui CMenuBridge]))

(defn create-menu-bridge
  "Create an AbstractContainerMenu proxy wrapping a Clojure container.

  proxy generates the implementing class at runtime inside the current
  DynamicClassLoader, so it works without AOT compilation.

  Args:
  - window-id:     int        - Forge menu container id
  - menu-type:     MenuType   - registered MenuType for this GUI
  - clj-container: map        - Clojure-side container (NodeContainer, etc.)"
  [window-id menu-type clj-container]
  (menu-core/create-menu-bridge
   window-id
   menu-type
   clj-container
    {:get-slot-layout gui/get-slot-layout
    :default-player-inventory-mode :full
    :call-super-removed? false
    :remove-log-message "Menu closed for player"
    :quick-move-error-prefix "Error in quickMoveStack:"}))