(ns my-mod.core
  (:require [my-mod.defs :as defs]
            [my-mod.util.log :as log]
            [my-mod.gui.api :as gui-api]
            [my-mod.gui.core :as gui-core]
            ;; Load all GUI definitions (so gui-dsl registry is populated)
            [my-mod.gui.definitions]
            [my-mod.events.metadata :as event-metadata]
            [my-mod.wireless.gui.matrix-network-handler :as matrix-net]
            [my-mod.wireless.gui.node-network-handler :as node-net]
            [my-mod.wireless.world-data :as wd]
            ;; Load all block definitions (so block-dsl registry is populated)
            [my-mod.block.wireless-node]
            [my-mod.block.wireless-matrix]
            [my-mod.block.solar-gen]
            ;; Load all item definitions (so item-dsl registry is populated)
            [my-mod.item.components]
            [my-mod.item.constraint-plate]
            [my-mod.item.mat-core]
            [my-mod.item.media]
            [my-mod.item.test-battery]))

(defn init
  "Core init hook invoked by per-version entry classes."
  []
  (log/info "Initializing core for mod-id=" defs/mod-id)
  ;; Initialize event metadata system
  (event-metadata/init-event-metadata!)
  ;; Initialize wireless world data system
  (wd/init-world-data!)
  ;; Register GUI network handlers
  (matrix-net/init!)
  (node-net/init!))

(defn on-block-right-click
  "Generic block right-click event handler.
  
  Dispatches to block-specific handlers registered in event metadata system.
  Platform code does not know which blocks have handlers.
  
  Args:
    ctx: Event context map with :x :y :z :player :world :block :block-id"
  [{:keys [x y z player world block block-id] :as ctx}]
  (log/info "Right-click event at (" x "," y "," z ") for block-id:" block-id)
  
  ;; Dispatch to block-specific handler if registered
  (when-let [handler (event-metadata/get-block-event-handler block-id :on-right-click)]
    (log/info "Dispatching to registered handler for block:" block-id)
    (handler ctx)))
