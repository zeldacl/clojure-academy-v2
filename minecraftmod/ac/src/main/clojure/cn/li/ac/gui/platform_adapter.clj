(ns cn.li.ac.gui.platform-adapter
  "AC-owned GUI platform callback installation.

  Loader modules must not import this namespace. AC calls `install-into-mcmod!`
  during content load to register container behavior into mcmod."
  (:require [cn.li.ac.gui.platform-adapter.dispatcher-api :as dispatcher-api]
            [cn.li.ac.gui.slot-validators :as slot-validators]
            [cn.li.mcmod.gui.adapter.platform-registry :as platform-registry]))

(defn install-into-mcmod!
  "Install AC GUI callbacks into mcmod platform-registry."
  []
  (slot-validators/register-default-slot-validators!)
  (platform-registry/register-gui-platform-impl!
    {:server-menu-sync! dispatcher-api/server-menu-sync!
     :safe-validate dispatcher-api/safe-validate
     :safe-close! dispatcher-api/safe-close!
     :slot-count dispatcher-api/slot-count
     :slot-get-item dispatcher-api/slot-get-item
     :slot-set-item! dispatcher-api/slot-set-item!
     :slot-changed! dispatcher-api/slot-changed!
     :slot-can-place? dispatcher-api/slot-can-place?
     :execute-quick-move-forge dispatcher-api/execute-quick-move-forge
     :get-container-type dispatcher-api/get-container-type
     :get-gui-id-for-container dispatcher-api/get-gui-id-for-container})
  nil)
