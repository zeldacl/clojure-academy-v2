(ns cn.li.mcmod.gui.registry-core
  "Unified GUI operations API (replacing legacy gui.adapter facade file)."
  (:require [cn.li.mcmod.gui.handler :as gui-handler]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.gui.adapter.platform-registry :as platform-registry]
            [cn.li.mcmod.gui.metadata :as metadata]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]))

(defonce ^:private screen-factories
  (atom {}))

(def register-gui-platform-impl! platform-registry/register-gui-platform-impl!)

(defn register-screen-factory!
  [screen-fn-kw screen-fn]
  (swap! screen-factories assoc (keyword screen-fn-kw) screen-fn)
  nil)

(defn get-screen-factory-fn
  [screen-fn-kw]
  (if-let [f (get @screen-factories (keyword screen-fn-kw))]
    f
    (throw (ex-info "Screen factory not registered"
                    {:screen-fn-kw screen-fn-kw}))))

(defn get-screen-factory-fn-kw [gui-id] (metadata/get-screen-factory-fn gui-id))
(defn get-all-gui-ids [] (metadata/get-all-gui-ids))
(defn get-display-name [gui-id] (metadata/get-display-name gui-id))
(defn get-gui-type [gui-id] (metadata/get-gui-type gui-id))
(defn get-registry-name [gui-id] (metadata/get-registry-name gui-id))
(defn get-slot-layout [gui-id] (metadata/get-slot-layout gui-id))
(defn get-slot-range [gui-id section] (metadata/get-slot-range gui-id section))

(defmulti register-gui-handler
  (fn [platform-type] platform-type))

(defmethod register-gui-handler :default [_platform-type]
  nil)

(defn init-gui-handler!
  [platform-type]
  (register-gui-handler platform-type)
  gui-handler/get-gui-handler)

(defn get-gui-handler [] (gui-handler/get-gui-handler))

(defn register-active-container!
  ([container] (container-state/register-active-container! container))
  ([owner container] (container-state/register-active-container! owner container)))
(defn unregister-active-container!
  ([container] (container-state/unregister-active-container! container))
  ([owner container] (container-state/unregister-active-container! owner container)))
(defn list-active-containers
  ([] (container-state/list-active-containers))
  ([owner] (container-state/list-active-containers owner)))
(defn register-player-container! [owner container] (container-state/register-player-container! owner container))
(defn unregister-player-container!
  ([owner] (container-state/unregister-player-container! owner))
  ([owner container] (container-state/unregister-player-container! owner container)))
(defn get-player-container [owner] (container-state/get-player-container owner))
(defn get-player-container-from-active [owner] (container-state/get-player-container-from-active owner))
(defn get-container-for-menu [menu] (container-state/get-container-for-menu menu))
(defn resolve-container-for-menu [menu] (container-state/resolve-container-for-menu menu))
(defn get-container-by-id
  [owner container-id]
  (container-state/get-container-by-id owner container-id))
(defn get-menu-container-id [menu] (container-state/get-menu-container-id menu))
(defn register-menu-container! [menu container] (container-state/register-menu-container! menu container))
(defn unregister-menu-container! [menu] (container-state/unregister-menu-container! menu))
(defn register-container-by-id!
  [owner container-id container]
  (container-state/register-container-by-id! owner container-id container))
(defn unregister-container-by-id!
  [owner container-id]
  (container-state/unregister-container-by-id! owner container-id))
(defn safe-tick! [container] (platform-registry/invoke-platform! :safe-tick! container))
(defn safe-validate [container player] (platform-registry/invoke-platform! :safe-validate container player))
(defn safe-sync! [container] (platform-registry/invoke-platform! :safe-sync! container))
(defn safe-close! [container] (platform-registry/invoke-platform! :safe-close! container))
(defn slot-count [container] (platform-registry/invoke-platform! :slot-count container))
(defn slot-get-item [container idx] (platform-registry/invoke-platform! :slot-get-item container idx))
(defn slot-set-item! [container idx item] (platform-registry/invoke-platform! :slot-set-item! container idx item))
(defn slot-changed! [container idx] (platform-registry/invoke-platform! :slot-changed! container idx))
(defn slot-can-place? [container idx stack] (platform-registry/invoke-platform! :slot-can-place? container idx stack))
(defn get-container-type [container] (platform-registry/invoke-platform! :get-container-type container))
(defn node-container? [container] (platform-registry/invoke-platform! :node-container? container))
(defn matrix-container? [container] (platform-registry/invoke-platform! :matrix-container? container))
(defn get-gui-id-for-container [container] (platform-registry/invoke-platform! :get-gui-id-for-container container))

(defn execute-quick-move-forge [menu container slot-index slot stack]
  (platform-registry/invoke-platform! :execute-quick-move-forge menu container slot-index slot stack))

(defn register-set-tab-handler!
  []
  (tabbed-gui/register-set-tab-handler!))
