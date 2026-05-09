(ns cn.li.forge1201.gui.bridge
  "Forge 1.20.1 GUI Bridge facade.

  Keeps public API stable while delegating concrete bridge logic to:
  - cn.li.forge1201.gui.menu-bridge
  - cn.li.forge1201.gui.provider-bridge"
  (:require [cn.li.mc1201.gui.bridge :as shared-bridge]
            [cn.li.forge1201.gui.menu-bridge :as menu-bridge]
            [cn.li.forge1201.gui.provider-bridge :as provider-bridge]))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn create-menu-provider
  "Create a Menu Provider for opening GUI
  
  Args:
  - gui-id: int (GUI identifier)
  - tile-entity: TileEntity instance
  
  Returns: MenuProvider instance"
  [gui-id tile-entity]
  (shared-bridge/create-menu-provider provider-bridge/create-menu-provider gui-id tile-entity))

(defn create-extended-menu-provider
  "Create an extended menu provider (Forge uses the same provider type)."
  [gui-id tile-entity]
  (shared-bridge/create-extended-menu-provider provider-bridge/create-extended-menu-provider gui-id tile-entity))

(defn create-extended-factory
  "Backward-compatible alias."
  [gui-id tile-entity]
  (shared-bridge/create-extended-factory provider-bridge/create-extended-menu-provider gui-id tile-entity))

(defn wrap-clojure-container
  "Wrap a Clojure container in Java AbstractContainerMenu
  
  Args:
  - window-id: int
  - menu-type: MenuType
  - clj-container: Clojure container
  
  Returns: AbstractContainerMenu instance"
  [window-id menu-type clj-container]
  (shared-bridge/wrap-clojure-container menu-bridge/create-menu-bridge window-id menu-type clj-container))
