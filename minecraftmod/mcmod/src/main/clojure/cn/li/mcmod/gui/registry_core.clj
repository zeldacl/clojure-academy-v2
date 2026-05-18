(ns cn.li.mcmod.gui.registry-core
  "Unified GUI operations API (replacing legacy gui.adapter facade file)."
  (:require [cn.li.mcmod.gui.handler :as gui-handler]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.gui.adapter.platform-registry :as platform-registry]))

(defonce ^:private resolved-vars
  (atom {}))

(defonce ^:private screen-factories
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

(defn- metadata-call
  [var-sym & args]
  (let [resolved (requiring-resolve var-sym)]
    (cond
      (and resolved (bound? resolved))
      (apply resolved args)

      :else
      (do
        (require 'cn.li.mcmod.protocol.metadata :reload)
        (let [resolved2 (requiring-resolve var-sym)]
          (when (and resolved2 (bound? resolved2))
            (apply resolved2 args)))))))

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

(defn get-screen-factory-fn-kw [gui-id]
  (metadata-call 'cn.li.mcmod.protocol.metadata/get-gui-screen-factory-fn-kw gui-id))

(defn get-all-gui-ids [] (or (metadata-call 'cn.li.mcmod.protocol.metadata/get-all-gui-ids) []))
(defn get-display-name [gui-id] (some-> (metadata-call 'cn.li.mcmod.protocol.metadata/get-gui-spec gui-id) :display-name))
(defn get-gui-type [gui-id] (some-> (metadata-call 'cn.li.mcmod.protocol.metadata/get-gui-spec gui-id) :gui-type))
(defn get-registry-name [gui-id] (metadata-call 'cn.li.mcmod.protocol.metadata/get-gui-registry-name gui-id))
(defn get-slot-layout [gui-id] (metadata-call 'cn.li.mcmod.protocol.metadata/get-gui-slot-layout gui-id))
(defn get-slot-range [gui-id section] (metadata-call 'cn.li.mcmod.protocol.metadata/get-gui-slot-range gui-id section))

(defmulti register-gui-handler
  (fn [platform-type] platform-type))

(defmethod register-gui-handler :default [_platform-type]
  nil)

(defn init-gui-handler!
  [platform-type]
  (register-gui-handler platform-type)
  gui-handler/get-gui-handler)

(defn get-gui-handler [] (gui-handler/get-gui-handler))

(defn register-active-container! [container] (container-state/register-active-container! container))
(defn unregister-active-container! [container] (container-state/unregister-active-container! container))
(defn list-active-containers [] (container-state/list-active-containers))
(defn register-player-container! [player container] (container-state/register-player-container! player container))
(defn unregister-player-container! [player] (container-state/unregister-player-container! player))
(defn get-player-container [player] (container-state/get-player-container player))
(defn get-player-container-from-active [player] (container-state/get-player-container-from-active player))
(defn get-container-for-menu [menu] (container-state/get-container-for-menu menu))
(defn resolve-container-for-menu [menu] (container-state/resolve-container-for-menu menu))
(defn get-container-by-id [container-id] (container-state/get-container-by-id container-id))
(defn get-menu-container-id [menu] (container-state/get-menu-container-id menu))
(defn register-menu-container! [menu container] (container-state/register-menu-container! menu container))
(defn unregister-menu-container! [menu] (container-state/unregister-menu-container! menu))
(defn register-container-by-id! [container-id container] (container-state/register-container-by-id! container-id container))
(defn unregister-container-by-id! [container-id] (container-state/unregister-container-by-id! container-id))
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

(defn get-menu-type [platform gui-id] (platform-registry/invoke-platform! :get-menu-type platform gui-id))
(defn register-menu-type! [platform gui-id menu-type] (platform-registry/invoke-platform! :register-menu-type! platform gui-id menu-type))
(defn execute-quick-move-forge [menu container slot-index slot stack]
  (platform-registry/invoke-platform! :execute-quick-move-forge menu container slot-index slot stack))

(defn register-set-tab-handler!
  []
  (delegate 'cn.li.mcmod.gui.tabbed-gui/register-set-tab-handler!))
