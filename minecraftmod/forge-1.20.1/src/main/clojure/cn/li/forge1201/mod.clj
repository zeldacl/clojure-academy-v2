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
    [cn.li.mcmod.config :as modid]
    [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.fml.event.lifecycle FMLClientSetupEvent]))

(defn- current-mod-id
  []
  modid/*mod-id*)

(def ^:private forge-mod-cache-lock
  (Object.))

(defn- cached-once!
  [v init-fn]
  (or (var-get v)
      (locking forge-mod-cache-lock
        (or (var-get v)
            (let [created (init-fn)]
              (alter-var-root v (constantly created))
              created)))))

(def ^:private ^:dynamic *base-properties* nil)
(def ^:private ^:dynamic *carrier-properties* nil)
(def ^:private ^:dynamic *blocks-register* nil)
(def ^:private ^:dynamic *items-register* nil)
(def ^:private ^:dynamic *creative-tabs-register* nil)

(defn base-properties []
  (cached-once! #'*base-properties* #(bootstrap/create-stone-properties)))

(defn carrier-properties []
  (cached-once! #'*carrier-properties* #(bootstrap/carrier-block-properties (base-properties))))

(defn blocks-register []
  (cached-once! #'*blocks-register* #(bootstrap/create-blocks-register (current-mod-id))))

(defn items-register []
  (cached-once! #'*items-register* #(bootstrap/create-items-register (current-mod-id))))

(defn creative-tabs-register []
  (cached-once! #'*creative-tabs-register* #(bootstrap/create-creative-tabs-register (current-mod-id))))

(defn- aot-compilation?
  []
  (boolean *compile-files*))

(defn- clojurephant-compilation?
  []
  (boolean (System/getProperty "clojure.server.clojurephant")))

(defn- datagen-run?
  []
  (or (= "true" (System/getProperty "ac.datagen"))
      (= "true" (System/getProperty "forge.datagen"))
      (= "true" (System/getProperty "fabric.datagen"))))

;; BlockEntity types
(def ^:private ^:dynamic *block-entities-register* nil)
(def ^:private ^:dynamic *fluid-types-register* nil)
(def ^:private ^:dynamic *fluids-register* nil)
(def ^:private ^:dynamic *sounds-register* nil)
(def ^:private ^:dynamic *effects-register* nil)
(def ^:private ^:dynamic *particle-types-register* nil)

(defn block-entities-register []
  (cached-once! #'*block-entities-register* #(bootstrap/create-block-entity-types-register (current-mod-id))))

(defn fluid-types-register []
  (cached-once! #'*fluid-types-register* #(bootstrap/create-fluid-types-register (current-mod-id))))

(defn fluids-register []
  (cached-once! #'*fluids-register* #(bootstrap/create-fluids-register (current-mod-id))))

(defn sounds-register []
  (cached-once! #'*sounds-register* #(bootstrap/create-sounds-register (current-mod-id))))

(defn effects-register []
  (cached-once! #'*effects-register* #(bootstrap/create-effects-register (current-mod-id))))

(defn particle-types-register []
  (cached-once! #'*particle-types-register* #(bootstrap/create-particle-types-register (current-mod-id))))

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
(defn on-common-setup [_event]
  (log/info "FMLCommonSetupEvent - Common setup phase")
  (setup-common/run-common-setup!))

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
          (gui-init/init-client!)
          (when-let [install-i18n! (side/resolve-client-fn 'cn.li.mc1201.client.i18n 'install-client-i18n!)]
            (install-i18n!))
          (if-let [init-client! (side/resolve-client-fn 'cn.li.forge1201.client.init 'init-client)]
            (init-client!)
            (log/error "Client-side detected but client init failed to load")))))))

;; ============================================================================
;; Constructor Implementation
;; ============================================================================

;; Runtime bootstrap entrypoint for Java @Mod bridge.
(defn start-forge-mod! []
  (let [aot? (aot-compilation?)
        cphant? (clojurephant-compilation?)
        check? (= "true" (System/getProperty "ac.check.clojure"))]
    (log/info "[BOOTSTRAP_TRACE] start-forge-mod! enter"
              {:aot aot?
               :clojurephant cphant?
               :ac-check check?
               :compile-files (boolean *compile-files*)})
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
      aot? cphant? check?)))
