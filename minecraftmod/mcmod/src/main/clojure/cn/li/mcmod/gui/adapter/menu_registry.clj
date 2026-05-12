(ns cn.li.mcmod.gui.adapter.menu-registry
  (:require [cn.li.mcmod.gui.adapter.platform-registry :as platform-registry]))

(defn get-menu-type [platform gui-id] (platform-registry/invoke-platform! :get-menu-type platform gui-id))
(defn register-menu-type! [platform gui-id menu-type] (platform-registry/invoke-platform! :register-menu-type! platform gui-id menu-type))
(defn execute-quick-move-forge [menu container slot-index slot stack]
  (platform-registry/invoke-platform! :execute-quick-move-forge menu container slot-index slot stack))
