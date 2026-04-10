(ns cn.li.ac.core
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.resource :as platform-res]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.config :as mcmod-config]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.mcmod.gui.adapter :as gui-adapter]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.ac.gui.platform-adapter :as platform-gui]
            [cn.li.ac.wireless.shared.screen-factory :as screen-factory]
            [cn.li.ac.gui.slot-validators :as slot-validators]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.mcmod.events.metadata :as event-metadata]
            [cn.li.mcmod.block.multiblock-core :as mb-core]
            [cn.li.mcmod.events.dispatcher :as event-dispatcher]
            [cn.li.ac.wireless.data.world :as wd]
            [cn.li.ac.energy.legacy-item-api-bridge :as legacy-item-api-bridge]
            [cn.li.ac.wireless.legacy-api-bridge :as legacy-api-bridge]
            [cn.li.ac.config.registry :as config-registry]
            ;; Auto-registration system
            [cn.li.ac.registry.content-namespaces :as content-ns]
            [cn.li.ac.registry.hooks :as hooks]))

(defonce runtime-content-loader
  (delay
    ;; Load all content namespaces (triggers DSL macros and hook registration)
    (content-ns/load-all!)

    ;; Auto-register GUI screen factories AFTER GUI DSL metadata is loaded.
    ;; If done before `content-ns/load-all!`, GUI IDs can still be empty and
    ;; screen creation falls back to the black placeholder screen.
    (let [gui-ids (gui-adapter/get-all-gui-ids)]
      (log/info "Registering screen factories for GUI IDs:" gui-ids)
      (doseq [gui-id gui-ids]
        (when-let [gui-type (platform-gui/get-gui-type gui-id)]
          (let [screen-fn-kw (keyword (str "create-" (name gui-type) "-screen"))]
            (gui-adapter/register-screen-factory!
              screen-fn-kw
              (partial screen-factory/create-screen gui-type))
            (log/info "Registered screen factory" screen-fn-kw "for GUI ID" gui-id)))))

    ;; Initialize event metadata system AFTER content is loaded
    ;; This syncs block event handlers from the DSL registry
    (event-metadata/init-event-metadata!)
    ;; Call all registered network handlers
    (hooks/call-all-network-handlers!)
    ;; Register generic set-tab handler for tabbed GUIs (inv-window + panels)
    (tabbed-gui/register-set-tab-handler!)))

(defn activate-runtime-content!
  "Load and initialize AC runtime content once.
  Safe to call repeatedly from platform lifecycle code."
  []
  (force runtime-content-loader)
  nil)

(defn init
  "Core init hook invoked by per-version entry classes."
  []
  ;; Bind mod-id into mcmod config so resource helpers and resource-location
  ;; injection become consistent across modules.
  (alter-var-root #'mcmod-config/*mod-id*
                  (constantly modid/MOD-ID))

  ;; Register slot validators used by platform GUI slot implementations.
  (slot-registry/register-slot-validator! :energy
                                            slot-validators/energy-item-validator)
  (slot-registry/register-slot-validator! :plate
                                            slot-validators/constraint-plate-validator)
  (slot-registry/register-slot-validator! :core
                                            slot-validators/matrix-core-validator)
  (slot-registry/register-slot-validator! :output
                                            slot-validators/output-slot-validator)

  ;; Inject GUI platform callbacks into mcmod.
  ;; This keeps `mcmod` free from any direct `cn.li.ac.*` calls.
  (gui-adapter/register-gui-platform-impl!
    {:set-client-container! #'platform-gui/set-client-container!
     :clear-client-container! #'platform-gui/clear-client-container!
     :get-client-container #'platform-gui/get-client-container

     :register-active-container! #'platform-gui/register-active-container!
     :unregister-active-container! #'platform-gui/unregister-active-container!

     :register-player-container! #'platform-gui/register-player-container!
     :unregister-player-container! #'platform-gui/unregister-player-container!

     :get-player-container #'platform-gui/get-player-container
     :get-player-container-from-active #'platform-gui/get-player-container-from-active

     :get-container-for-menu #'platform-gui/get-container-for-menu
     :get-container-by-id #'platform-gui/get-container-by-id
     :get-menu-container-id #'platform-gui/get-menu-container-id

     :register-menu-container! #'platform-gui/register-menu-container!
     :unregister-menu-container! #'platform-gui/unregister-menu-container!

     :register-container-by-id! #'platform-gui/register-container-by-id!
     :unregister-container-by-id! #'platform-gui/unregister-container-by-id!

     :safe-tick! #'platform-gui/safe-tick!
     :safe-validate #'platform-gui/safe-validate
     :safe-sync! #'platform-gui/safe-sync!
     :safe-close! #'platform-gui/safe-close!

     :slot-count #'platform-gui/slot-count
     :slot-get-item #'platform-gui/slot-get-item
     :slot-set-item! #'platform-gui/slot-set-item!
     :slot-changed! #'platform-gui/slot-changed!
     :slot-can-place? #'platform-gui/slot-can-place?

     :get-container-type #'platform-gui/get-container-type

     :get-gui-id-for-container #'platform-gui/get-gui-id-for-container
     :get-menu-type #'platform-gui/get-menu-type
     :register-menu-type! #'platform-gui/register-menu-type!})

  ;; Inject resource-location for mcmod (gui.components, client.resources) so they resolve paths without requiring config.modid
  (alter-var-root #'platform-res/*resource-location-fn*
                  (constantly (fn [namespace path]
                               (if (nil? namespace)
                                 (mcmod-config/resource-location path)
                                 (mcmod-config/resource-location namespace path)))))
  (log/info "Initializing core for mod-id=" modid/MOD-ID)
  ;; Initialize wireless world data system
  (wd/init-world-data!)
  ;; Install Java item energy API bridge onto AC energy operations.
  (legacy-item-api-bridge/install-item-energy-api-bridge!)
  ;; Install Java wireless query API bridge onto the wireless system.
  (legacy-api-bridge/install-wireless-query-api-bridge!)
  ;; Register distributed AC config descriptors/defaults into the shared registry.
  (config-registry/init-configs!))

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

;; Register client-side initialization callback
(defn- init-client-renderers
  "Load renderer namespaces to trigger auto-registration.
  Called by mcmod during client initialization."
  []
  (hooks/load-all-client-renderers!))

;; Register the callback with mcmod lifecycle system
(when-let [register-fn (requiring-resolve 'cn.li.mcmod.lifecycle/register-client-init!)]
  (register-fn init-client-renderers))
