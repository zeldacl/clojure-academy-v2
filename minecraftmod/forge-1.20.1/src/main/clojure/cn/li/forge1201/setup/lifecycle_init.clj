(ns cn.li.forge1201.setup.lifecycle-init
  "Forge lifecycle initialization coordinator.
  
  Breaks mod.clj initialization into 6 explicit phases:
  
  1. Platform Init - PlatformBootstraps/initialize + init-from-java
  2. Runtime Content - lifecycle/run-runtime-content-activation!
  3. Resource Init - BlockState properties and DSL initialization
  4. Content Registration - All block/item/fluid/entity/sound/effect/particle registration
  5. Mod Bus Setup - Deferred register + lifecycle listener wiring
  6. Common Setup - Event subscription and runtime adapter initialization
  
  This separates concerns so each phase is testable and reusable."
  (:require [cn.li.forge1201.init :as init]
            [cn.li.forge1201.config.game-config :as game-config]
            [cn.li.forge1201.setup.mod-bus :as setup-mod-bus]
            [cn.li.ac.config.gameplay :as gameplay]
            [cn.li.mc1201.lifecycle.orchestrator :as lifecycle-orchestrator]
            [cn.li.mc1201.lifecycle.platform-manifest :as platform-manifest]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.mcmod.util.log :as log]
            [cn.li.forge1201.setup.common :as setup-common])
  (:import [cn.li.mcmod.platform.spi PlatformBootstraps]))

;; =============================================================================
;; Phase 1: Platform Initialization
;; =============================================================================

(defn init-platform!
  "Initialize platform abstractions (Forge-specific bootstrap).
  
  Must happen first - sets up NBT/BlockPos/World interop layer."
  []
  (log/info "[LIFECYCLE] Phase 1: Platform initialization")
  (when-not (PlatformBootstraps/initialize "forge-1.20.1")
    (log/error "No platform bootstrap provider found for forge-1.20.1")
    (throw (ex-info "Forge platform bootstrap provider missing"
                    {:platform "forge-1.20.1"})))
  ;; Core init sets *resource-location-fn* for mcmod gui.components/client.resources
  (init/init-from-java)
  (log/info "[LIFECYCLE] Phase 1: Platform initialization complete"))

;; =============================================================================
;; Phase 2: Runtime Content Activation
;; =============================================================================

(defn activate-runtime-content!
  "Activate shared runtime content registry and lifecycle hooks."
  []
  (log/info "[LIFECYCLE] Phase 2: Runtime content activation")
  (lifecycle/run-runtime-content-activation!)
  (log/info "[LIFECYCLE] Phase 2: Runtime content activation complete"))

(defn bind-gameplay-config!
  "Bind platform gameplay config provider into shared gameplay bridge."
  []
  (log/info "[LIFECYCLE] Phase 2b: Gameplay config binding")
  (gameplay/install-config-bridge! (game-config/provider-map))
  (log/info "[LIFECYCLE] Phase 2b: Gameplay config binding complete"))

;; =============================================================================
;; Phase 3: Resource Initialization
;; =============================================================================

(defn init-resource-definitions!
  "Initialize BlockState properties and DSL systems.
  
  Must happen before content registration so Property objects are ready."
  []
  (log/info "[LIFECYCLE] Phase 3: Resource definition initialization")
  (when-let [init-props! (requiring-resolve 'cn.li.mc1201.block.blockstate-properties/init-all-properties!)]
    (init-props!))
  (log/info "[LIFECYCLE] Phase 3: Resource definition initialization complete"))

;; =============================================================================
;; Phase 4: Content Registration
;; =============================================================================

(defn register-all-content!
  "Register all blocks, items, fluids, entities, sounds, effects, particles.
  
  Called from mod.clj via setup-common/run-common-setup! or similar.
  Assumes phase 1-3 have completed."
  [registration-steps]
  (log/info "[LIFECYCLE] Phase 4: Content registration" {:registrations (count registration-steps)})
  (doseq [register-step registration-steps]
    (register-step))
  (log/info "[LIFECYCLE] Phase 4: Content registration complete"))

;; =============================================================================
;; Phase 5: Mod Bus Setup
;; =============================================================================

(defn setup-mod-bus!
  "Wire mod event bus: config + registry + lifecycle + capability phases.
  
  Registers DeferredRegister instances and lifecycle listeners."
  [opts]
  (log/info "[LIFECYCLE] Phase 5: Mod bus setup")
  (setup-mod-bus/run-registration-phases! opts)
  (log/info "[LIFECYCLE] Phase 5: Mod bus setup complete"))

;; =============================================================================
;; Phase 6: Common Setup
;; =============================================================================

(defn run-common-setup!
  "Run common setup side effects (event wiring, GUI init, runtime adapters).
  
  Called during FMLCommonSetupEvent or in mod constructor after phases 1-5."
  []
  (log/info "[LIFECYCLE] Phase 6: Common setup")
  (setup-common/run-common-setup!)
  (log/info "[LIFECYCLE] Phase 6: Common setup complete"))

;; =============================================================================
;; Orchestration
;; =============================================================================

(defn init-lifecycle!
  "Run complete Forge initialization lifecycle (all 6 phases).
  
  This is the high-level orchestrator called from mod.clj constructor."
  [opts registration-steps]
  (try
    (lifecycle-orchestrator/run-lifecycle!
     (platform-manifest/build-lifecycle
      :forge-1.20.1
      {:init-platform! init-platform!
       :activate-runtime-content! activate-runtime-content!
       :bind-gameplay-config! bind-gameplay-config!
       :init-resource-definitions! init-resource-definitions!
       :register-content! #(register-all-content! registration-steps)
       :setup-mod-bus! #(setup-mod-bus! opts)
       :run-common-setup! run-common-setup!}))
    (catch Exception e
      (log/error "Forge initialization lifecycle failed" e)
      (throw e))))

(defn init-lifecycle-with-error-handling!
  "Run lifecycle with AOT/checkClojure error handling.
  
  Returns [state exception] for use with gen-class :init contract.
  - state: [] (empty state)
  - exception: nil on success, ex-info on failure (with type :bootstrap-skip)"
  [opts registration-steps aot? cphant? check?]
  (if (or aot? cphant? check?)
    (do
      (log/warn "[LIFECYCLE] Skipping bootstrap-sensitive path"
                {:reason {:aot aot? :clojurephant cphant? :ac-check check?}})
      [[] nil])
    (try
      (init-lifecycle! opts registration-steps)
      [[] nil]
      (catch IllegalArgumentException e
        (let [msg (some-> e .getMessage str)]
          (if (and msg (.contains msg "Not bootstrapped"))
            (do
              (log/warn "Skipping Forge initialization during checkClojure: Minecraft registries not bootstrapped")
              [[] nil])
            (throw e)))))))
