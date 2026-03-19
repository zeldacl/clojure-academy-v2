(ns my-mod.fabric1201.gui.impl
  "Fabric 1.20.1 GUI implementations"
  (:require [cn.li.mcmod.gui.api :as gui-api]
            [cn.li.mcmod.gui.core :as gui-core]
            [cn.li.mcmod.util.log :as log]))

;; GUI implementation for Fabric 1.20.1
(defmethod gui-api/open-gui :fabric-1.20.1
  [_ player-data]
  (log/info "Fabric 1.20.1: Opening GUI for player" (:player player-data))
  ;; TODO: Implement Fabric GUI opening logic
  ;; This would use Fabric's ExtendedScreenHandlerFactory
  (log/info "GUI opened (Fabric implementation pending)"))

(defmethod gui-api/register-menu-type :fabric-1.20.1
  [_ menu-id menu-factory]
  (log/info "Fabric 1.20.1: Registering menu type" menu-id)
  ;; TODO: Implement Fabric menu type registration
  ;; This would use Registry.register with BuiltInRegistries.MENU
  (log/info "Menu type registered (Fabric implementation pending)"))

;; Button click handler
(defn on-button-clicked [button-id]
  (log/info "Fabric 1.20.1: Button clicked:" button-id)
  (case button-id
    "destroy" (gui-core/on-destroy-button-clicked)
    (log/info "Unknown button:" button-id)))

;; Slot change handler
(defn on-slot-changed [slot-index item-stack]
  (log/info "Fabric 1.20.1: Slot" slot-index "changed to" item-stack)
  ;; Update the slots atom in gui-core
  (swap! gui-core/gui-slots assoc slot-index item-stack))
