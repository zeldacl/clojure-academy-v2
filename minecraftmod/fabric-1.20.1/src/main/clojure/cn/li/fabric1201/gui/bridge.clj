(ns cn.li.fabric1201.gui.bridge
  "Fabric 1.20.1 GUI Bridge facade.

  Keeps public API stable while delegating concrete bridge logic to:
  - cn.li.fabric1201.gui.menu-bridge
  - cn.li.fabric1201.gui.provider-bridge"
  (:require [cn.li.mc1201.gui.bridge :as shared-bridge]
            [cn.li.fabric1201.gui.menu-bridge :as menu-bridge]
            [cn.li.fabric1201.gui.provider-bridge :as provider-bridge]))

(defn create-menu-provider [gui-id tile-entity]
  (shared-bridge/create-menu-provider provider-bridge/create-menu-provider gui-id tile-entity))

(defn create-extended-menu-provider [gui-id tile-entity]
  (shared-bridge/create-extended-menu-provider provider-bridge/create-extended-menu-provider gui-id tile-entity))

(defn create-extended-screen-handler-factory [gui-id tile-entity]
  (shared-bridge/create-extended-screen-handler-factory provider-bridge/create-extended-menu-provider gui-id tile-entity))

(defn wrap-clojure-container [sync-id handler-type clj-container]
  (shared-bridge/wrap-clojure-container menu-bridge/create-menu-bridge sync-id handler-type clj-container))
