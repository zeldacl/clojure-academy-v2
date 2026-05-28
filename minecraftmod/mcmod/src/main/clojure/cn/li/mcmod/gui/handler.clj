(ns cn.li.mcmod.gui.handler
  "GUI infrastructure for platform adapters.

   This namespace hosts:
   - IGuiHandler protocol
  - RegistryGuiHandler record implementation

   Container runtime state now lives in an explicit component created from
   `cn.li.mcmod.gui.container-state` and owned by the handler instance.
   Platform adapters and game content should not re-implement these building blocks."
  (:require [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.gui.registry :as gui-registry]
            [cn.li.mcmod.platform.world :as pworld]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; GUI Handler Protocol
;; ============================================================================

(defprotocol IGuiHandler
  "Protocol for GUI opening and container creation"
  (get-server-container [this gui-id player world pos]
    "Create server-side container")
  (get-client-gui [this gui-id player world pos]
    "Create client-side GUI screen"))

;; ============================================================================
;; GUI Handler Helpers
;; ============================================================================

(defn get-gui-config
  "Get GUI spec/config by gui-id (int)."
  [gui-id]
  (gui-registry/has-gui-id? gui-id))

;; Default handler implementation (driven by GUI config stored in `gui-registry`).
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

;; ============================================================================
;; Global Handler Instance
;; ============================================================================

(defn create-gui-handler-runtime
  ([] (create-gui-handler-runtime {}))
  ([{:keys [state*]}]
   {:cn.li.mcmod.gui.handler/runtime ::gui-handler-runtime
    :state* (or state* (atom nil))}))

(def ^:dynamic *gui-handler-runtime* nil)

(defonce ^:private installed-gui-handler-runtime
  (create-gui-handler-runtime))

(defn- gui-handler-atom []
  (:state* (or *gui-handler-runtime* installed-gui-handler-runtime)))

(declare get-gui-handler)

(defn get-container-state-runtime
  []
  (:container-runtime (get-gui-handler)))

(defn get-gui-handler
  "Get the global GUI handler instance."
  []
  (or @(gui-handler-atom)
      (reset! (gui-handler-atom)
              (->RegistryGuiHandler (container-state/create-container-state-runtime)))))