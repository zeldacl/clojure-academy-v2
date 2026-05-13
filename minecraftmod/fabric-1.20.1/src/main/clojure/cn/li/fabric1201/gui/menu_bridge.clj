(ns cn.li.fabric1201.gui.menu-bridge
  "Fabric 1.20.1 AbstractContainerMenu wrapper.

  Uses runtime `proxy` + a tiny Java bridge class (CMenuBridge) so protected
  AbstractContainerMenu APIs can be invoked from Clojure safely."
  (:require [cn.li.mcmod.gui.registry-core :as gui]
            [cn.li.mc1201.gui.menu-bridge-core :as menu-core])
  (:import [cn.li.mc1201.gui CMenuBridge]))

(defn create-menu-bridge
  [window-id menu-type clj-container]
  (menu-core/create-menu-bridge
   window-id
   menu-type
   clj-container
    {:get-slot-layout gui/get-slot-layout
    :default-player-inventory-mode :full
    :call-super-removed? true
    :remove-log-message "Fabric menu closed for player"
    :quick-move-error-prefix "Error in Fabric quickMoveStack:"}))

(defn create-screen-handler-bridge [window-id menu-type clj-container]
  (create-menu-bridge window-id menu-type clj-container))
