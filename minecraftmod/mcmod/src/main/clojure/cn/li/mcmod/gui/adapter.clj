(ns cn.li.mcmod.gui.adapter
  "Facade for unified GUI operations split into focused submodules."
  (:require [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.gui.handler :as gui-handler]
            [cn.li.mcmod.gui.adapter.platform-registry :as platform-registry]
            [cn.li.mcmod.gui.adapter.screen-factory :as screen-factory]
            [cn.li.mcmod.gui.adapter.container-registry :as container-registry]
            [cn.li.mcmod.gui.adapter.menu-registry :as menu-registry]))

(defonce ^:private resolved-vars
  (atom {}))

(defn- resolve-var
  [var-sym]
  (or (@resolved-vars var-sym)
      (let [v (requiring-resolve var-sym)]
        (swap! resolved-vars assoc var-sym v)
        v)))

(defn- delegate
  [var-sym & args]
  (apply (resolve-var var-sym) args))

(def register-gui-platform-impl! platform-registry/register-gui-platform-impl!)
(def register-screen-factory! screen-factory/register-screen-factory!)
(def get-screen-factory-fn screen-factory/get-screen-factory-fn)

(defn get-screen-factory-fn-kw [gui-id]
  (registry-metadata/get-gui-screen-factory-fn-kw gui-id))

(defn get-all-gui-ids [] (registry-metadata/get-all-gui-ids))
(defn get-display-name [gui-id] (some-> (registry-metadata/get-gui-spec gui-id) :display-name))
(defn get-gui-type [gui-id] (some-> (registry-metadata/get-gui-spec gui-id) :gui-type))
(defn get-registry-name [gui-id] (registry-metadata/get-gui-registry-name gui-id))
(defn get-slot-layout [gui-id] (registry-metadata/get-gui-slot-layout gui-id))
(defn get-slot-range [gui-id section] (registry-metadata/get-gui-slot-range gui-id section))

(defmulti register-gui-handler
  (fn [platform-type] platform-type))

(defmethod register-gui-handler :default [_platform-type]
  nil)

(defn init-gui-handler!
  [platform-type]
  (register-gui-handler platform-type)
  gui-handler/get-gui-handler)

(defn get-gui-handler [] (gui-handler/get-gui-handler))

(def set-client-container! container-registry/set-client-container!)
(def clear-client-container! container-registry/clear-client-container!)
(def get-client-container container-registry/get-client-container)
(def register-active-container! container-registry/register-active-container!)
(def unregister-active-container! container-registry/unregister-active-container!)
(def register-player-container! container-registry/register-player-container!)
(def unregister-player-container! container-registry/unregister-player-container!)
(def get-player-container container-registry/get-player-container)
(def get-player-container-from-active container-registry/get-player-container-from-active)
(def get-container-for-menu container-registry/get-container-for-menu)
(def get-container-by-id container-registry/get-container-by-id)
(def get-menu-container-id container-registry/get-menu-container-id)
(def register-menu-container! container-registry/register-menu-container!)
(def unregister-menu-container! container-registry/unregister-menu-container!)
(def register-container-by-id! container-registry/register-container-by-id!)
(def unregister-container-by-id! container-registry/unregister-container-by-id!)
(def safe-tick! container-registry/safe-tick!)
(def safe-validate container-registry/safe-validate)
(def safe-sync! container-registry/safe-sync!)
(def safe-close! container-registry/safe-close!)
(def slot-count container-registry/slot-count)
(def slot-get-item container-registry/slot-get-item)
(def slot-set-item! container-registry/slot-set-item!)
(def slot-changed! container-registry/slot-changed!)
(def slot-can-place? container-registry/slot-can-place?)
(def get-container-type container-registry/get-container-type)
(def node-container? container-registry/node-container?)
(def matrix-container? container-registry/matrix-container?)
(def get-gui-id-for-container container-registry/get-gui-id-for-container)

(def get-menu-type menu-registry/get-menu-type)
(def register-menu-type! menu-registry/register-menu-type!)
(def execute-quick-move-forge menu-registry/execute-quick-move-forge)

(defn register-set-tab-handler!
  []
  (delegate 'cn.li.mcmod.gui.tabbed-gui/register-set-tab-handler!))
