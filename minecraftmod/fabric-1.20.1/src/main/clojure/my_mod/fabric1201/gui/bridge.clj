(ns cn.li.fabric1201.gui.bridge
  "Fabric 1.20.1 GUI Bridge facade.

  Keeps public API stable while delegating concrete bridge logic to:
  - cn.li.fabric1201.gui.menu-bridge
  - cn.li.fabric1201.gui.provider-bridge"
  (:require [cn.li.fabric1201.gui.menu-bridge :as menu-bridge]
            [cn.li.fabric1201.gui.provider-bridge :as provider-bridge]))

(defn create-menu-provider
  "Create a simple menu provider."
  [gui-id tile-entity]
  (provider-bridge/create-menu-provider gui-id tile-entity))

(defn create-extended-menu-provider
  "Create an extended menu provider with packet opening data."
  [gui-id tile-entity]
  (provider-bridge/create-extended-menu-provider gui-id tile-entity))

(defn create-screen-handler-factory
  "Backward-compatible alias."
  [gui-id tile-entity]
  (create-menu-provider gui-id tile-entity))

(defn create-extended-screen-handler-factory
  "Backward-compatible alias."
  [gui-id tile-entity]
  (create-extended-menu-provider gui-id tile-entity))

(defn create-extended-factory
  "Backward-compatible alias."
  [gui-id tile-entity]
  (create-extended-menu-provider gui-id tile-entity))

(defn wrap-clojure-container
  "Wrap Clojure container in Fabric ScreenHandler."
  [sync-id handler-type clj-container]
  (menu-bridge/create-menu-bridge sync-id handler-type clj-container))
