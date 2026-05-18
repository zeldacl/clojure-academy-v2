(ns cn.li.forge1201.mod
  "Forge 1.20.1 main mod class - generated with gen-class"
  (:require [cn.li.forge1201.integration.bootstrap :as bootstrap]
    [cn.li.forge1201.registry.content-registration :as content-registration]
    [cn.li.forge1201.registry.creative-tab :as creative-tab]
    [cn.li.forge1201.setup.common :as setup-common]
    [cn.li.forge1201.setup.forge-lifecycle-coordinator :as lifecycle-coordinator]
    [cn.li.forge1201.integration.side :as side]
    [cn.li.forge1201.registry.state :as registry-state]
    [cn.li.forge1201.integration.events :as events]
    [cn.li.forge1201.gui.init :as gui-init]
    [cn.li.forge1201.adapter.gui-registry :as gui-registry-impl]
    [cn.li.mcmod.config :as modid]
    [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.fml.event.lifecycle FMLClientSetupEvent])
  (:gen-class
   :name com.example.my_mod1201.MyMod1201Clj
   :prefix "mod-"
   :init init
   :state state
   :constructors {[] []}
   :methods [[onRightClickBlock [net.minecraftforge.event.entity.player.PlayerInteractEvent$RightClickBlock] void]
     [commonSetup [net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent] void]
     [clientSetup [net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent] void]]))
 
(def mod-id modid/*mod-id*)

(def base-properties
  (delay (bootstrap/create-stone-properties)))

(def carrier-properties
  (delay (bootstrap/carrier-block-properties @base-properties)))

(def blocks-register
  (delay (bootstrap/create-blocks-register mod-id)))

(def items-register
  (delay (bootstrap/create-items-register mod-id)))

(def creative-tabs-register
  (delay (bootstrap/create-creative-tabs-register mod-id)))

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
(defonce block-entities-register
  (delay (bootstrap/create-block-entity-types-register mod-id)))

(defonce fluid-types-register
  (delay (bootstrap/create-fluid-types-register mod-id)))

(defonce fluids-register
  (delay (bootstrap/create-fluids-register mod-id)))

(defonce sounds-register
  (delay (bootstrap/create-sounds-register mod-id)))

(defonce effects-register
  (delay (bootstrap/create-effects-register mod-id)))

(defonce particle-types-register
  (delay (bootstrap/create-particle-types-register mod-id)))

;; Storage for registered blocks and items (populated during initialization)
(def registered-blocks registry-state/registered-blocks)
(def registered-items registry-state/registered-items)
(def registered-entities registry-state/registered-entities)
(def registered-block-entities registry-state/registered-block-entities)
(def registered-fluid-types registry-state/registered-fluid-types)
(def registered-fluids-source registry-state/registered-fluids-source)
(def registered-fluids-flowing registry-state/registered-fluids-flowing)
(def registered-sounds registry-state/registered-sounds)
(def registered-effects registry-state/registered-effects)
(def registered-particles registry-state/registered-particles)

(defn- build-registration-context
  []
  {:mod-id mod-id
   :blocks-register (force blocks-register)
   :items-register (force items-register)
   :block-entities-register (force block-entities-register)
   :fluid-types-register (force fluid-types-register)
   :fluids-register (force fluids-register)
   :sounds-register (force sounds-register)
   :effects-register (force effects-register)
   :particle-types-register (force particle-types-register)
   :registered-fluids-source registered-fluids-source
   :base-properties @base-properties
   :carrier-properties @carrier-properties})

(defn get-registered-entity-type
  "Get a registered EntityType by entity-id."
  [entity-id]
  (if-let [f (requiring-resolve 'cn.li.forge1201.registry.state/get-registered-entity-type)]
    (f entity-id)
    nil))

(defn get-registered-block-entity-type
  "Get a registered BlockEntityType by tile-id or block-id."
  [tile-or-block-id]
  (if-let [f (requiring-resolve 'cn.li.forge1201.registry.state/get-registered-block-entity-type)]
    (f tile-or-block-id)
    nil))

;; ============================================================================
;; Helper Functions for Registry Queries
;; ============================================================================

(defn get-registered-block
  "Get a registered block by its DSL ID.
  
  Args:
    block-id: String - DSL block identifier (e.g., \"demo-block\")
  
  Returns:
    RegistryObject - The registered block, or nil if not found"
  [block-id]
  (if-let [f (requiring-resolve 'cn.li.forge1201.registry.state/get-registered-block)]
    (f block-id)
    nil))

(defn get-registered-item
  "Get a registered item by its DSL ID.
  
  Args:
    item-id: String - DSL item identifier (e.g., \"demo-item\")
  
  Returns:
    RegistryObject - The registered item, or nil if not found"
  [item-id]
  (if-let [f (requiring-resolve 'cn.li.forge1201.registry.state/get-registered-item)]
    (f item-id)
    nil))

(defn get-registered-block-item
  "Get a registered block item by its block ID.
  
  Args:
    block-id: String - DSL block identifier (e.g., \"demo-block\")
  
  Returns:
    RegistryObject - The registered block item, or nil if not found"
  [block-id]
  (if-let [f (requiring-resolve 'cn.li.forge1201.registry.state/get-registered-block-item)]
    (f block-id)
    nil))

(defn get-registered-fluid-source
  [fluid-id]
  (if-let [f (requiring-resolve 'cn.li.forge1201.registry.state/get-registered-fluid-source)]
    (f fluid-id)
    nil))

(defn get-registered-fluid-flowing
  [fluid-id]
  (if-let [f (requiring-resolve 'cn.li.forge1201.registry.state/get-registered-fluid-flowing)]
    (f fluid-id)
    nil))

(defn- registration-steps
  []
  [(fn []
     (content-registration/register-core-content! (build-registration-context)))
   (fn []
     (log/info "Registering Forge creative tab...")
     (creative-tab/register-creative-tab! (force creative-tabs-register) mod-id))
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
    (lifecycle-coordinator/init-lifecycle-with-error-handling!
      {:datagen-run? (datagen-run?)
       :on-common-setup on-common-setup
       :on-client-setup on-client-setup
       :sounds-register (force sounds-register)
       :effects-register (force effects-register)
       :particle-types-register (force particle-types-register)
       :fluid-types-register (force fluid-types-register)
       :fluids-register (force fluids-register)
       :blocks-register (force blocks-register)
       :items-register (force items-register)
       :block-entities-register (force block-entities-register)
       :creative-tabs-register (force creative-tabs-register)
       :gui-menu-register (force gui-registry-impl/menu-register)}
      (registration-steps)
      aot? cphant? check?)))
;; (defn start-repl-safe []
;;   (let [cl (.getContextClassLoader (Thread/currentThread))]
;;     (nrepl/start-server :port 7888 :handler (nrepl/default-handler))
;;     ;; 纭繚 REPL 绾跨▼鑳借闂埌 Minecraft 鐨勭被
;;     (.setContextClassLoader (Thread/currentThread) cl)))

;; ============================================================================
;; Gen-class Method Implementations
;; ============================================================================

;; Gen-class method implementations (required by gen-class contract)
(defn mod-commonSetup [_this event]
  (on-common-setup event))

(defn mod-clientSetup [_this event]
  (on-client-setup event))

;; Event handler method (required by gen-class, but not used directly in 1.20.1)
(defn mod-onRightClickBlock [_this event]
  (events/handle-right-click-event event))
