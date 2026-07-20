(ns cn.li.forge1201.mod
  "Forge 1.20.1 loader entry implemented via Java @Mod bridge."
  (:require [cn.li.forge1201.integration.bootstrap :as bootstrap]
    [cn.li.forge1201.registry.content-registration :as content-registration]
    [cn.li.forge1201.registry.creative-tab :as creative-tab]
    [cn.li.forge1201.setup.common :as setup-common]
    [cn.li.forge1201.setup.lifecycle-init :as lifecycle-init]
    [cn.li.forge1201.integration.side :as side]
    [cn.li.forge1201.registry.state :as registry-state]
    [cn.li.forge1201.gui.init :as gui-init]
    [cn.li.forge1201.adapter.gui-registry :as gui-registry-impl]
    [cn.li.forge1201.client.key-mapping-adapter :as key-mapping-adapter]
    [cn.li.forge1201.client.keyboard-event-handler :as keyboard-event-handler]
    [cn.li.mc1201.key-scheme-provider-core :as key-scheme-core]
    [cn.li.mc1201.vanilla-input-control-core :as vanilla-control]
    [cn.li.mcmod.spi.key-scheme-provider :as key-scheme-spi]
    [cn.li.mcmod.spi.vanilla-input-control :as vanilla-spi]
    [cn.li.mcmod.lifecycle :as lifecycle]
    [cn.li.mcmod.aot :as aot]
    [cn.li.mcmod.config :as modid]
    [cn.li.mcmod.runtime.deferred :as deferred]
    [cn.li.mcmod.framework :as fw]
    [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.fml.event.lifecycle FMLClientSetupEvent
                                                   FMLCommonSetupEvent]))

(defn- current-mod-id
  []
  modid/mod-id)

;; ============================================================================
;; Unified deferred holders (replaces old cached-once! + dynamic vars)
;; ============================================================================

;; Basic properties
(def ^:private base-props-holder
  (deferred/deferred #(bootstrap/create-stone-properties)))

(def ^:private carrier-props-holder
  (deferred/deferred #(bootstrap/carrier-block-properties @base-props-holder)))

;; Core registries
(def ^:private blocks-reg-holder
  (deferred/deferred #(bootstrap/create-blocks-register (current-mod-id))))

(def ^:private items-reg-holder
  (deferred/deferred #(bootstrap/create-items-register (current-mod-id))))

(def ^:private creative-tabs-reg-holder
  (deferred/deferred #(bootstrap/create-creative-tabs-register (current-mod-id))))

;; Additional registries
(def ^:private block-entities-reg-holder
  (deferred/deferred #(bootstrap/create-block-entity-types-register (current-mod-id))))

(def ^:private fluid-types-reg-holder
  (deferred/deferred #(bootstrap/create-fluid-types-register (current-mod-id))))

(def ^:private fluids-reg-holder
  (deferred/deferred #(bootstrap/create-fluids-register (current-mod-id))))

(def ^:private sounds-reg-holder
  (deferred/deferred #(bootstrap/create-sounds-register (current-mod-id))))

(def ^:private effects-reg-holder
  (deferred/deferred #(bootstrap/create-effects-register (current-mod-id))))

(def ^:private particle-types-reg-holder
  (deferred/deferred #(bootstrap/create-particle-types-register (current-mod-id))))

;; ============================================================================
;; Getter functions (delegates to deferred holders)
;; ============================================================================

(defn base-properties []
  @base-props-holder)

(defn carrier-properties []
  @carrier-props-holder)

(defn blocks-register []
  @blocks-reg-holder)

(defn items-register []
  @items-reg-holder)

(defn creative-tabs-register []
  @creative-tabs-reg-holder)

(defn block-entities-register []
  @block-entities-reg-holder)

(defn fluid-types-register []
  @fluid-types-reg-holder)

(defn fluids-register []
  @fluids-reg-holder)

(defn sounds-register []
  @sounds-reg-holder)

(defn effects-register []
  @effects-reg-holder)

(defn particle-types-register []
  @particle-types-reg-holder)

(defn- datagen-run?
  []
  (or (= "true" (System/getProperty "ac.datagen"))
      (= "true" (System/getProperty "forge.datagen"))
      (= "true" (System/getProperty "fabric.datagen"))))

(defn- build-registration-context
  []
  {:mod-id (current-mod-id)
  :blocks-register (blocks-register)
  :items-register (items-register)
  :block-entities-register (block-entities-register)
  :fluid-types-register (fluid-types-register)
  :fluids-register (fluids-register)
  :sounds-register (sounds-register)
  :effects-register (effects-register)
  :particle-types-register (particle-types-register)
   :registered-fluids-source registry-state/registered-fluids-source-snapshot
  :base-properties (base-properties)
  :carrier-properties (carrier-properties)})

(defn- registration-steps
  []
  [(fn []
     (content-registration/register-core-content! (build-registration-context)))
   (fn []
     (log/info "Registering Forge creative tab...")
     (creative-tab/register-creative-tab! (creative-tabs-register) (current-mod-id)))
   (fn []
     (gui-registry-impl/register-menu-types!))])

;; ============================================================================
;; Setup Phase Helpers (must be defined before start-forge-mod!)
;; ============================================================================

;; Helper: Common setup phase (subscribed to FMLCommonSetupEvent during bootstrap)
(defn on-common-setup [^FMLCommonSetupEvent event]
  (log/info "FMLCommonSetupEvent - Common setup phase")
  ;; Forge fires lifecycle events on parallel mod-loading workers; defer registry
  ;; and network wiring to the main thread to avoid racing Forge internals.
  (.enqueueWork event
                (reify Runnable
                  (run [_]
                    (setup-common/run-common-setup!)))))

;; Helper: Client setup phase (called from event handler)
(defn on-client-setup [^FMLClientSetupEvent event]
  (log/info "FMLClientSetupEvent - Client setup phase")
  ;; Forge fires this event on parallel mod-loading workers. BlockEntityRenderer
  ;; registration (and related client registry work) must run on the main client
  ;; thread via enqueueWork or renders silently never attach.
  (when (side/client-side?)
    (.enqueueWork event
      (reify Runnable
        (run [_]
          ;; ===== Platform SPI Installation (before AC bootstrap) =====
          ;; Install Forge-specific SPI implementations that AC will use
          (try
            (key-scheme-spi/install-provider! (key-scheme-core/get-spi-implementation))
            (vanilla-spi/install-suppressor! (vanilla-control/get-spi-implementation))
            (catch Exception e
              (log/warn e "Failed to install keyboard input SPI providers")
              (log/stacktrace "Failed to install keyboard input SPI providers" e)))
          
          ;; ===== Content Keybinding Initialization (post-SPI) =====
          ;; Run content-registered post-SPI init callbacks (e.g. AC keybindings)
          (try
            (lifecycle/run-post-spi-client-init!)
            (catch Exception e
              (log/error e "Failed to run post-SPI content keybinding init")
              (log/stacktrace "Failed to run post-SPI content keybinding init" e)))
          
          ;; ===== Forge KeyMapping Registration =====
          ;; Register all :alternative scheme inputs as Forge KeyMappings
          (try
            (key-mapping-adapter/register-all-keybindings-from-ac!)
            (catch Exception e
              (log/error e "Failed to register Forge KeyMappings")
              (log/stacktrace "Failed to register Forge KeyMappings" e)))
          
          ;; ===== Forge Event Handler Installation =====
          ;; Install InputEvent$Key listener to route Forge events
          (try
            (keyboard-event-handler/install-forge-event-handler!)
            (catch Exception e
              (log/error e "Failed to install Forge keyboard event handler")
              (log/stacktrace "Failed to install Forge keyboard event handler" e)))
          
          ;; ===== Standard Client Initialization =====
          (gui-init/init-client!)
          (when-let [install-i18n! (side/resolve-client-fn 'cn.li.mc1201.client.i18n/install-client-i18n!)]
            (install-i18n!))
          (if-let [init-client! (side/resolve-client-fn 'cn.li.forge1201.client.init/init-client)]
            (init-client!)
            (log/error "Client-side detected but client init failed to load")))))))

;; ============================================================================
;; Constructor Implementation
;; ============================================================================

;; Runtime bootstrap entrypoint for Java @Mod bridge.
(defn start-forge-mod! []
  (log/info "[BOOTSTRAP_TRACE] start-forge-mod! enter"
            {:compile-context (aot/compile-context)})
  (when-let [fw-inst (fw/create-framework)]
    (alter-var-root #'fw/framework (constantly fw-inst)))
  (lifecycle-init/init-lifecycle-with-error-handling!
    {:datagen-run? (datagen-run?)
     :on-common-setup on-common-setup
     :on-client-setup on-client-setup
    :sounds-register (sounds-register)
    :effects-register (effects-register)
    :particle-types-register (particle-types-register)
    :fluid-types-register (fluid-types-register)
    :fluids-register (fluids-register)
    :blocks-register (blocks-register)
    :items-register (items-register)
    :block-entities-register (block-entities-register)
    :creative-tabs-register (creative-tabs-register)
    :gui-menu-register (gui-registry-impl/menu-register)}
    (registration-steps)
    false))
