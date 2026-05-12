(ns cn.li.ac.gui.platform-adapter.metadata-api
  "Metadata/query API exposed to platform GUI integration."
  (:require [cn.li.mcmod.gui.metadata :as metadata]))

(defn valid-gui-ids
  "Return a set of all registered wireless GUI ids."
  []
  (set (metadata/get-all-gui-ids)))

(def get-display-name metadata/get-display-name)
(def get-gui-type metadata/get-gui-type)
(def get-registry-name metadata/get-registry-name)
(def get-menu-type metadata/get-menu-type)
(def register-menu-type! metadata/register-menu-type!)
(def get-all-gui-ids metadata/get-all-gui-ids)
(def get-screen-factory-fn metadata/get-screen-factory-fn)
(def get-screen-factory-fn-keyword metadata/get-screen-factory-fn)
(def get-screen-factory-fn-kw metadata/get-screen-factory-fn)

(defn gui-slot-layouts
  "Return a map of gui-id -> slot-layout for all registered GUIs."
  []
  (into {}
        (keep (fn [gui-id]
                (when-let [layout (metadata/get-slot-layout gui-id)]
                  [gui-id layout])))
        (metadata/get-all-gui-ids)))

(def get-slot-layout metadata/get-slot-layout)
(def get-slot-range metadata/get-slot-range)
