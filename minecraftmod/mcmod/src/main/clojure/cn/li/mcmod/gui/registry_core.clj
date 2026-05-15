(ns cn.li.mcmod.gui.registry-core
  "Unified GUI operations API (replacing legacy gui.adapter facade file)."
  (:require [cn.li.mcmod.gui.handler :as gui-handler]
            [cn.li.mcmod.gui.adapter.platform-registry :as platform-registry]
            [cn.li.mcmod.gui.adapter.runtime-api :as runtime-api]))

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
(def register-screen-factory! runtime-api/register-screen-factory!)
(def get-screen-factory-fn runtime-api/get-screen-factory-fn)

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

(def set-client-container! runtime-api/set-client-container!)
(def clear-client-container! runtime-api/clear-client-container!)
(def get-client-container runtime-api/get-client-container)
(def register-active-container! runtime-api/register-active-container!)
(def unregister-active-container! runtime-api/unregister-active-container!)
(def register-player-container! runtime-api/register-player-container!)
(def unregister-player-container! runtime-api/unregister-player-container!)
(def get-player-container runtime-api/get-player-container)
(def get-player-container-from-active runtime-api/get-player-container-from-active)
(def get-container-for-menu runtime-api/get-container-for-menu)
(def get-container-by-id runtime-api/get-container-by-id)
(def get-menu-container-id runtime-api/get-menu-container-id)
(def register-menu-container! runtime-api/register-menu-container!)
(def unregister-menu-container! runtime-api/unregister-menu-container!)
(def register-container-by-id! runtime-api/register-container-by-id!)
(def unregister-container-by-id! runtime-api/unregister-container-by-id!)
(def safe-tick! runtime-api/safe-tick!)
(def safe-validate runtime-api/safe-validate)
(def safe-sync! runtime-api/safe-sync!)
(def safe-close! runtime-api/safe-close!)
(def slot-count runtime-api/slot-count)
(def slot-get-item runtime-api/slot-get-item)
(def slot-set-item! runtime-api/slot-set-item!)
(def slot-changed! runtime-api/slot-changed!)
(def slot-can-place? runtime-api/slot-can-place?)
(def get-container-type runtime-api/get-container-type)
(def node-container? runtime-api/node-container?)
(def matrix-container? runtime-api/matrix-container?)
(def get-gui-id-for-container runtime-api/get-gui-id-for-container)

(def get-menu-type runtime-api/get-menu-type)
(def register-menu-type! runtime-api/register-menu-type!)
(def execute-quick-move-forge runtime-api/execute-quick-move-forge)

(defn register-set-tab-handler!
  []
  (delegate 'cn.li.mcmod.gui.tabbed-gui/register-set-tab-handler!))
