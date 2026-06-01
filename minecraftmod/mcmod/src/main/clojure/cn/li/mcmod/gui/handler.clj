(ns cn.li.mcmod.gui.handler
  "GUI handler protocol, platform handler registration, and handler runtime."
  (:require [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.gui.registry :as gui-registry]
            [cn.li.mcmod.platform.world :as pworld]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

(defprotocol IGuiHandler
  (get-server-container [this gui-id player world pos]
    "Create server-side container")
  (get-client-gui [this gui-id player world pos]
    "Create client-side GUI screen"))

(defn get-gui-config
  "Return true when gui-id is registered."
  [gui-id]
  (gui-registry/has-gui-id? gui-id))

(defrecord RegistryGuiHandler [container-runtime]
  IGuiHandler
  (get-server-container [_ gui-id player world pos]
    (let [tile-entity (pworld/world-get-tile-entity* world pos)
          cfg? (get-gui-config gui-id)
          container-fn (gui-registry/get-container-fn gui-id)]
      (if (and tile-entity cfg? container-fn)
        (do
          (log/debug "Creating container for player"
                     (entity/player-get-name player)
                     "gui" gui-id)
          (container-fn tile-entity player))
        (do
          (when-not cfg?
            (log/warn "Unknown GUI ID:" gui-id))
          (when (and cfg? (nil? container-fn))
            (log/warn "Missing :container-fn for GUI ID:" gui-id))
          nil))))
  (get-client-gui [_ gui-id player world pos]
    (let [tile-entity (pworld/world-get-tile-entity* world pos)
          cfg? (get-gui-config gui-id)
          container-fn (gui-registry/get-container-fn gui-id)
          screen-fn (gui-registry/get-screen-fn gui-id)]
      (if (and tile-entity cfg? container-fn screen-fn)
        (do
          (log/debug "Creating GUI for player"
                     (entity/player-get-name player)
                     "gui" gui-id)
          (let [container (container-fn tile-entity player)]
            (screen-fn container nil player)))
        (do
          (when-not cfg?
            (log/warn "Unknown GUI ID:" gui-id))
          (when (and cfg? (nil? container-fn))
            (log/warn "Missing :container-fn for GUI ID:" gui-id))
          (when (and cfg? (nil? screen-fn))
            (log/warn "Missing :screen-fn for GUI ID:" gui-id))
          nil)))))

(defn create-gui-handler-runtime
  ([] (create-gui-handler-runtime {}))
  ([{:keys [state* container-runtime]}]
   {:cn.li.mcmod.gui.handler/runtime ::gui-handler-runtime
    :state* (or state* (atom nil))
    :container-runtime (or container-runtime (container-state/installed-runtime))}))

(defonce ^:private installed-gui-handler-runtime
  (create-gui-handler-runtime))

(def ^:dynamic *gui-handler-runtime*
  installed-gui-handler-runtime)

(defn- gui-handler-atom []
  (:state* *gui-handler-runtime*))

(defn- container-runtime-for-handler []
  (:container-runtime *gui-handler-runtime*))

(defmulti register-gui-handler
  (fn [platform-type] platform-type))

(defmethod register-gui-handler :default [_platform-type]
  nil)

(defn get-gui-handler
  []
  (or @(gui-handler-atom)
      (reset! (gui-handler-atom)
              (->RegistryGuiHandler (container-runtime-for-handler)))))

(defn init-gui-handler!
  [platform-type]
  (register-gui-handler platform-type)
  (get-gui-handler))
