(ns cn.li.ac.gui.tabbed-gui
  "Re-export mcmod tabbed-gui to maintain backward compatibility."
  (:require [cn.li.mcmod.gui.tabbed-gui :as mcmod-tabbed]))

(def inv-tab-index mcmod-tabbed/inv-tab-index)
(def slots-active? mcmod-tabbed/slots-active?)
(def tabbed-container? mcmod-tabbed/tabbed-container?)
(def set-tab-index-by-container-id! mcmod-tabbed/set-tab-index-by-container-id!)
(def get-tab-index-by-container-id mcmod-tabbed/get-tab-index-by-container-id)
(def clear-tab-index-by-container-id! mcmod-tabbed/clear-tab-index-by-container-id!)
(def slots-active-for-menu? mcmod-tabbed/slots-active-for-menu?)
(def page-id->index mcmod-tabbed/page-id->index)
(def index->page-id mcmod-tabbed/index->page-id)
(def set-tab-msg-id mcmod-tabbed/set-tab-msg-id)
(def register-set-tab-handler! mcmod-tabbed/register-set-tab-handler!)
(def send-set-tab! mcmod-tabbed/send-set-tab!)
(def attach-tab-sync! mcmod-tabbed/attach-tab-sync!)
