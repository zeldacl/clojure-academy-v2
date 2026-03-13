(ns my-mod.core
  (:require [my-mod.defs :as defs]
            [my-mod.util.log :as log]
            [my-mod.platform.resource :as platform-res]
            [my-mod.config.modid :as modid]
            ;; Load all GUI definitions (so gui-dsl registry is populated)
            [my-mod.gui.definitions]
            [my-mod.events.metadata :as event-metadata]
            [my-mod.block.multiblock-core :as mb-core]
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
            [my-mod.item.media]))

(defn init
  "Core init hook invoked by per-version entry classes."
  []
  ;; Inject resource-location for mcmod (gui.components, client.resources) so they resolve paths without requiring config.modid
  (alter-var-root #'platform-res/*resource-location-fn*
                  (constantly (fn [namespace path]
                               (if (nil? namespace)
                                 (modid/resource-location path)
                                 (modid/resource-location namespace path)))))
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
  [{:keys [x y z block-id] :as ctx}]
  (let [routed-ctx (mb-core/route-to-controller-context ctx)
        routed-block-id (:block-id routed-ctx)]
    (log/info "Right-click event at (" x "," y "," z ") for block-id:" block-id
              "-> routed:" routed-block-id)
  
    ;; Dispatch to block-specific handler if registered
    (when-let [handler (event-metadata/get-block-event-handler routed-block-id :on-right-click)]
      (log/info "Dispatching to registered handler for block:" routed-block-id)
      (handler routed-ctx))))

(defn on-block-place
  "Generic block place event handler.
  
  Dispatches to block-specific :on-place handlers registered in event metadata."
  [{:keys [x y z block-id] :as ctx}]
  (log/info "Place event at (" x "," y "," z ") for block-id:" block-id)
  (if-let [precheck-ret (mb-core/precheck-controller-place ctx)]
    precheck-ret
    (let [handler-ret (when-let [handler (event-metadata/get-block-event-handler block-id :on-place)]
                        (log/info "Dispatching to registered :on-place handler for block:" block-id)
                        (handler ctx))
          core-ret (mb-core/post-place-controller! ctx)]
      (or core-ret handler-ret))))

(defn on-block-break
  "Generic block break event handler.

  Routes part blocks to their controller block handlers, then applies
  generic structure cleanup in mcmod." 
  [{:keys [x y z block-id] :as ctx}]
  (let [routed-ctx (mb-core/route-to-controller-context ctx)
        routed-block-id (:block-id routed-ctx)]
    (log/info "Break event at (" x "," y "," z ") for block-id:" block-id
              "-> routed:" routed-block-id)
    (let [handler-ret (when-let [handler (event-metadata/get-block-event-handler routed-block-id :on-break)]
                        (log/info "Dispatching to registered :on-break handler for block:" routed-block-id)
                        (handler routed-ctx))
          core-ret (mb-core/apply-structure-break! ctx routed-ctx)]
      (merge (or handler-ret {}) (or core-ret {})))))
