(ns cn.li.ac.core
  (:require [cn.li.ac.defs :as defs]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.resource :as platform-res]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.config :as mcmod-config]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.mcmod.gui.adapter :as gui-adapter]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.ac.wireless.gui.screen-factory :as screen-factory]
            [cn.li.ac.gui.slot-validators :as slot-validators]
            ;; Load all GUI definitions (so gui-dsl registry is populated)
            [cn.li.ac.gui.definitions]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.mcmod.events.metadata :as event-metadata]
            [cn.li.mcmod.block.multiblock-core :as mb-core]
            [cn.li.mcmod.events.dispatcher :as event-dispatcher]
            [cn.li.ac.wireless.gui.matrix-network-handler :as matrix-net]
            [cn.li.ac.wireless.gui.node-network-handler :as node-net]
            [cn.li.ac.wireless.gui.generator-network-handler :as gen-net]
            [cn.li.ac.wireless.world-data :as wd]
            ;; Load all block definitions (so block-dsl registry is populated)
            [cn.li.ac.block.wireless-node]
            [cn.li.ac.block.wireless-matrix]
            [cn.li.ac.block.solar-gen]
            ;; Load all item definitions (so item-dsl registry is populated)
            [cn.li.ac.item.components]
            [cn.li.ac.item.constraint-plate]
            [cn.li.ac.item.mat-core]
            [cn.li.ac.item.media]))

(defn init
  "Core init hook invoked by per-version entry classes."
  []
  ;; Bind mod-id into mcmod config so resource helpers and resource-location
  ;; injection become consistent across modules.
  (alter-var-root #'mcmod-config/*mod-id*
                  (constantly modid/MOD-ID))

  ;; Register client screen factories into the unified mcmod GUI adapter.
  (gui-adapter/register-screen-factory! :create-node-screen
                                         screen-factory/create-node-screen)
  (gui-adapter/register-screen-factory! :create-matrix-screen
                                         screen-factory/create-matrix-screen)
  (gui-adapter/register-screen-factory! :create-solar-screen
                                         screen-factory/create-solar-screen)

  ;; Register slot validators used by platform GUI slot implementations.
  (slot-registry/register-slot-validator! :energy
                                            slot-validators/energy-item-validator)
  (slot-registry/register-slot-validator! :plate
                                            slot-validators/constraint-plate-validator)
  (slot-registry/register-slot-validator! :core
                                            slot-validators/matrix-core-validator)
  (slot-registry/register-slot-validator! :output
                                            slot-validators/output-slot-validator)

  ;; Inject resource-location for mcmod (gui.components, client.resources) so they resolve paths without requiring config.modid
  (alter-var-root #'platform-res/*resource-location-fn*
                  (constantly (fn [namespace path]
                               (if (nil? namespace)
                                 (mcmod-config/resource-location path)
                                 (mcmod-config/resource-location namespace path)))))
  (log/info "Initializing core for mod-id=" defs/mod-id)
  ;; Initialize event metadata system
  (event-metadata/init-event-metadata!)
  ;; Initialize wireless world data system
  (wd/init-world-data!)
  ;; Register GUI network handlers
  (matrix-net/init!)
  (node-net/init!)
  (gen-net/init!)
  ;; Register generic set-tab handler for tabbed GUIs (inv-window + panels)
  (tabbed-gui/register-set-tab-handler!))

(defn on-block-right-click
  "Backwards-compatible forwarding shim. Actual implementation lives in
   `cn.li.mcmod.events.dispatcher`." 
  [ctx]
  (event-dispatcher/on-block-right-click ctx))

(defn on-block-place
  "Backwards-compatible forwarding shim. Actual implementation lives in
   `cn.li.mcmod.events.dispatcher`."
  [ctx]
  (event-dispatcher/on-block-place ctx))

(defn on-block-break
  "Backwards-compatible forwarding shim. Actual implementation lives in
   `cn.li.mcmod.events.dispatcher`."
  [ctx]
  (event-dispatcher/on-block-break ctx))

;; Phase1.4/Phase2: register content init hook for platform adapters.
(lifecycle/register-content-init! #'init)
