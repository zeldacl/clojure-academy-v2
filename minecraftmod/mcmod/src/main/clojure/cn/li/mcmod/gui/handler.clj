(ns cn.li.mcmod.gui.handler
  "GUI infrastructure for platform adapters.

   This namespace hosts:
   - IGuiHandler protocol
   - WirelessGuiHandler record implementation
   - container state atoms (active/registered containers)

   Platform adapters and game content should not re-implement these building blocks."
  (:require [cn.li.mcmod.gui.dsl :as gui-dsl]
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
  (gui-dsl/get-gui-by-gui-id gui-id))

;; Default handler implementation (driven by GUI config stored in `gui-dsl`).
(defrecord WirelessGuiHandler []
  IGuiHandler
  (get-server-container [_ gui-id player world pos]
    (let [tile-entity (pworld/world-get-tile-entity world pos)
          cfg (get-gui-config gui-id)]
      (if (and tile-entity cfg)
        (do
          (log/info "Creating container for player"
                    (entity/player-get-name player)
                    "gui" gui-id)
          ((:container-fn cfg) tile-entity player))
        (do
          (when-not cfg
            (log/warn "Unknown GUI ID:" gui-id))
          nil))))
  (get-client-gui [_ gui-id player world pos]
    (let [tile-entity (pworld/world-get-tile-entity world pos)
          cfg (get-gui-config gui-id)]
      (if (and tile-entity cfg)
        (do
          (log/info "Creating GUI for player"
                    (entity/player-get-name player)
                    "gui" gui-id)
          (let [container ((:container-fn cfg) tile-entity player)]
            ((:screen-fn cfg) container nil player)))
        (do
          (when-not cfg
            (log/warn "Unknown GUI ID:" gui-id))
          nil)))))

;; ============================================================================
;; Global Handler Instance
;; ============================================================================

(defonce ^:private gui-handler
  (atom nil))

(defn get-gui-handler
  "Get the global GUI handler instance."
  []
  (or @gui-handler
      (reset! gui-handler (->WirelessGuiHandler))))

;; ============================================================================
;; Container State Atoms
;; ============================================================================

(defonce active-containers
  ;; Containers active for server tick updates.
  (atom #{}))

(defonce player-containers
  ;; Open tabbed containers keyed by player UUID (server-side lookup).
  (atom {}))

(defonce menu-containers
  ;; Map from AbstractContainerMenu instance -> container.
  (atom {}))

(defonce containers-by-id
  ;; Map from containerId/windowId -> container.
  (atom {}))

(defonce client-container
  ;; Client-side active container reference used by screen factories.
  (atom nil))

