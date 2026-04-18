(ns cn.li.forge1201.mod
  "Forge 1.20.1 main mod class - generated with gen-class"
  (:require [cn.li.forge1201.bootstrap :as bootstrap]
            [cn.li.forge1201.init :as init]
            [cn.li.forge1201.side :as side]
            [cn.li.forge1201.registry :as registry]
            [cn.li.forge1201.events :as events]
            [cn.li.forge1201.gui.init :as gui-init]
            [cn.li.forge1201.gui.registry-impl :as gui-registry-impl]
            [cn.li.forge1201.runtime.lifecycle :as runtime-lifecycle]
            [cn.li.forge1201.runtime.item-handler :as runtime-item-handler]
            [cn.li.forge1201.platform-impl :as platform-impl]
            [cn.li.forge1201.config.bridge :as config-bridge]
            ;; platform-impl is loaded lazily during runtime mod-init to avoid
            ;; triggering Minecraft class initialization during AOT/checkClojure.
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.i18n :as i18n]
             [cn.li.mcmod.util.log :as log]
             [cn.li.forge1201.wireless-imc :as wireless-imc]
             [cn.li.forge1201.integration.forge-energy :as forge-energy]
             [cn.li.forge1201.integration.ic2-energy :as ic2-energy])
  (:import [net.minecraft.world.level.block Block]
           [net.minecraft.world.level.material Fluid FlowingFluid]
           [net.minecraft.world.level.block.state BlockBehaviour BlockBehaviour$Properties]
           [cn.li.forge1201.block.entity ScriptedBlockEntity]
           [cn.li.forge1201.entity ModEntities]
           [cn.li.forge1201.sound ModSounds]
           [net.minecraft.world.item Item Item$Properties BlockItem CreativeModeTab Items]
           [net.minecraft.world.level ItemLike]
           [net.minecraft.network.chat Component]
           [net.minecraftforge.fluids FluidType ForgeFlowingFluid]
           [net.minecraftforge.registries DeferredRegister RegistryObject]
           [net.minecraftforge.fml.javafmlmod FMLJavaModLoadingContext]
           [net.minecraftforge.fml.event.lifecycle FMLCommonSetupEvent FMLClientSetupEvent]
       [net.minecraftforge.eventbus.api EventPriority IEventBus]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock]
          [net.minecraftforge.common.capabilities RegisterCapabilitiesEvent]
          [net.minecraftforge.fml.event.lifecycle InterModProcessEvent]
      [net.minecraftforge.fml InterModComms$IMCMessage]
          [cn.li.acapi.wireless WirelessImc])
  (:gen-class
   :name com.example.my_mod1201.MyMod1201Clj
   :prefix "mod-"
   :init init
   :state state
   :constructors {[] []}
   :methods [[onRightClickBlock [net.minecraftforge.event.entity.player.PlayerInteractEvent$RightClickBlock] void]
             [commonSetup [net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent] void]
             [clientSetup [net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent] void]]))

;; (alter-var-root #'*err* (fn [orig]
;;                           (proxy [java.io.PrintWriter] [orig]
;;                             (write
;;                               ([^String s]
;;                                (.write orig s)
;;                                ;; 褰撳彂鐜板弽灏勮鍛婂寘鍚?"flush" 鏃讹紝寮鸿鎵撳嵃褰撳墠璋冪敤鏍?;;                                (when (and (.contains s "Reflection warning") (.contains s "flush"))
;;                                  (.println orig "--- 鍙嶅皠婧愯拷韪紑濮?---")
;;                                  (doseq [st (.getStackTrace (Thread/currentThread))]
;;                                    (.println orig (str "  at " st)))
;;                                  (.println orig "--- 鍙嶅皠婧愯拷韪粨鏉?---")))
;;                               ([^String s ^Integer off ^Integer len]
;;                                (.write orig s off len))))))


;; Mod ID constant
(def mod-id modid/*mod-id*)

(defn- aot-compilation?
  []
  (boolean *compile-files*))

(defn- clojurephant-compilation?
  []
  (boolean (System/getProperty "clojure.server.clojurephant")))


;; Lazy block properties - only accessed during registration, not during namespace load
(defonce base-properties
  (delay (bootstrap/create-stone-properties)))

(defonce carrier-properties
  (delay (bootstrap/carrier-block-properties @base-properties)))

;; DeferredRegister instances
(defonce blocks-register
  ;; During AOT/checkClojure, Minecraft registries may not be bootstrapped.
  ;; Delay creation to avoid triggering bootstrap too early.
  (delay (bootstrap/create-blocks-register mod-id)))

(defonce items-register
  (delay (bootstrap/create-items-register mod-id)))

(defonce creative-tabs-register
  (delay (bootstrap/create-creative-tabs-register mod-id)))

;; BlockEntity types
(defonce block-entities-register
  (delay (bootstrap/create-block-entity-types-register mod-id)))

(defonce fluid-types-register
  (delay (bootstrap/create-fluid-types-register mod-id)))

(defonce fluids-register
  (delay (bootstrap/create-fluids-register mod-id)))

;; Storage for registered blocks and items (populated during initialization)
(defonce registered-blocks (atom {}))
(defonce registered-items (atom {}))
(defonce registered-block-entities (atom {}))
(defonce registered-fluid-types (atom {}))
(defonce registered-fluids-source (atom {}))
(defonce registered-fluids-flowing (atom {}))

(defn- has-block-state-properties?
  "Check if a block needs dynamic block state properties (via metadata).
  Platform code queries business layer instead of hardcoding block names."
  [block-id]
  (registry-metadata/has-block-state-properties? block-id))

;; Dynamic block registration using metadata
(defn register-all-blocks!
  "Register all blocks defined in DSL using metadata-driven approach.
  Platform code does not know specific block names."
  []
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (let [registry-name (registry-metadata/get-block-registry-name block-id)
          fluid-id (registry-metadata/get-fluid-id-for-block block-id)
          needs-dynamic-properties? (has-block-state-properties? block-id)
          has-be? (registry-metadata/has-block-entity? block-id)
          tile-id (when has-be?
                    (or (registry-metadata/get-block-tile-id block-id) block-id))
          registered-obj (.register ^DeferredRegister (force blocks-register) registry-name
                                    (reify java.util.function.Supplier
                                      (get [_]
                                        (let [get-props (requiring-resolve 'cn.li.forge1201.blockstate-properties/get-all-properties)]
                                          (cond
                                            fluid-id
                                            (when-let [fluid-source-ro (get @registered-fluids-source fluid-id)]
                                              (bootstrap/create-liquid-block
                                                (reify java.util.function.Supplier
                                                  (get [_]
                                                    (.get ^RegistryObject fluid-source-ro)))))
                                            (and needs-dynamic-properties? has-be?)
                                            (let [props (get-props block-id)]
                                              (bootstrap/create-carrier-scripted-dynamic-block block-id tile-id props @carrier-properties))
                                            needs-dynamic-properties?
                                            (let [props (get-props block-id)]
                                              (bootstrap/create-dynamic-state-block block-id props @base-properties))
                                            has-be?
                                            (bootstrap/create-carrier-scripted-block block-id tile-id @carrier-properties)
                                            :else
                                            (bootstrap/create-plain-block @base-properties))))))]
      (swap! registered-blocks assoc block-id registered-obj))))

(defn register-all-fluids!
  "Register all fluids declared in the fluid DSL."
  []
  (doseq [fluid-id (registry-metadata/get-all-fluid-ids)]
    (let [fluid-spec (registry-metadata/get-fluid-spec fluid-id)
          physical (:physical fluid-spec)
          rendering (:rendering fluid-spec)
          behavior (:behavior fluid-spec)
          block-spec (:block fluid-spec)
          registry-name (registry-metadata/get-fluid-registry-name fluid-id)
          flowing-name (str registry-name "_flowing")
          fluid-type-ro
          (.register ^DeferredRegister (force fluid-types-register) registry-name
                     (reify java.util.function.Supplier
                       (get [_]
                         (bootstrap/create-fluid-type
                           (:luminosity physical)
                           (:density physical)
                           (:viscosity physical)
                           (:temperature physical)
                           false
                           (:supports-boat physical)
                           (:still-texture rendering)
                           (:flowing-texture rendering)
                           (:overlay-texture rendering)
                           (:tint-color rendering)))))
          source-holder (atom nil)
          flowing-holder (atom nil)
          bucket-holder (atom nil)
          source-ro (.register ^DeferredRegister (force fluids-register) registry-name
                               (reify java.util.function.Supplier
                                 (get [_]
                                   (bootstrap/create-source-fluid
                                     (bootstrap/create-flowing-fluid-properties
                                       (reify java.util.function.Supplier
                                         (get [_] (.get ^RegistryObject fluid-type-ro)))
                                       (reify java.util.function.Supplier
                                         (get [_] (.get ^RegistryObject @source-holder)))
                                       (reify java.util.function.Supplier
                                         (get [_] (.get ^RegistryObject @flowing-holder)))
                                       (when (:has-bucket? block-spec)
                                         (reify java.util.function.Supplier
                                           (get [_] (.get ^RegistryObject @bucket-holder))))
                                       (when-let [block-id (:block-id block-spec)]
                                         (reify java.util.function.Supplier
                                           (get [_]
                                             (.get ^RegistryObject (get @registered-blocks block-id)))))
                                       (:slope-find-distance behavior)
                                       (:level-decrease-per-block behavior)
                                       (:tick-rate behavior)
                                       (:explosion-resistance behavior)
                                       (:can-convert-to-source physical))))))
          flowing-ro (.register ^DeferredRegister (force fluids-register) flowing-name
                                (reify java.util.function.Supplier
                                  (get [_]
                                    (bootstrap/create-flowing-fluid
                                      (bootstrap/create-flowing-fluid-properties
                                        (reify java.util.function.Supplier
                                          (get [_] (.get ^RegistryObject fluid-type-ro)))
                                        (reify java.util.function.Supplier
                                          (get [_] (.get ^RegistryObject @source-holder)))
                                        (reify java.util.function.Supplier
                                          (get [_] (.get ^RegistryObject @flowing-holder)))
                                        (when (:has-bucket? block-spec)
                                          (reify java.util.function.Supplier
                                            (get [_] (.get ^RegistryObject @bucket-holder))))
                                        (when-let [block-id (:block-id block-spec)]
                                          (reify java.util.function.Supplier
                                            (get [_]
                                              (.get ^RegistryObject (get @registered-blocks block-id)))))
                                        (:slope-find-distance behavior)
                                        (:level-decrease-per-block behavior)
                                        (:tick-rate behavior)
                                        (:explosion-resistance behavior)
                                        (:can-convert-to-source physical))))))]
      (reset! source-holder source-ro)
      (reset! flowing-holder flowing-ro)
      (swap! registered-fluid-types assoc fluid-id fluid-type-ro)
      (swap! registered-fluids-source assoc fluid-id source-ro)
      (swap! registered-fluids-flowing assoc fluid-id flowing-ro)
      (when (:has-bucket? block-spec)
        (let [bucket-ro (.register ^DeferredRegister (force items-register) (:bucket-registry-name block-spec)
                                   (reify java.util.function.Supplier
                                     (get [_]
                                       (bootstrap/create-fluid-bucket
                                         (reify java.util.function.Supplier
                                           (get [_] (.get ^RegistryObject source-ro)))))))]
          (reset! bucket-holder bucket-ro)
          (swap! registered-items assoc (:bucket-item-id block-spec) bucket-ro))))))

(defn register-scripted-tile-hooks!
  "Register metadata-driven scripted tile hooks from Tile DSL (or legacy block DSL).
  Registers lifecycle hooks under tile-id so one tile can be shared by many blocks."
  []
  (doseq [tile-id (registry-metadata/get-all-tile-ids)]
    (when-let [spec (registry-metadata/get-tile-spec tile-id)]
      (let [tick-fn (:tick-fn spec)
            read-nbt-fn (:read-nbt-fn spec)
            write-nbt-fn (:write-nbt-fn spec)
            tile-kind (:tile-kind spec)]
        (when (or tick-fn read-nbt-fn write-nbt-fn tile-kind)
          (tile-logic/register-tile-logic! tile-id
                                           {:tile-kind tile-kind
                                            :tick-fn tick-fn
                                            :read-nbt-fn read-nbt-fn
                                            :write-nbt-fn write-nbt-fn}))))))

;; BlockEntity registration: one BlockEntityType per tile-id
(defn register-block-entities!
  []
  (doseq [tile-id (registry-metadata/get-all-tile-ids)]
    (let [registry-name (registry-metadata/get-tile-registry-name tile-id)
          block-ids (registry-metadata/get-tile-block-ids tile-id)
          ;; Capture RegistryObjects now; resolve to Blocks later inside Supplier.get
          ros (keep (fn [block-id]
                      (when-let [ro (get @registered-blocks block-id)]
                        [block-id ro]))
                    block-ids)]
      (when (seq ros)
        (let [registered-obj
              (.register
                ^DeferredRegister (force block-entities-register)
                registry-name
                (reify java.util.function.Supplier
                  (get [_]
                    ;; Resolve RegistryObjects to Blocks at registration time
                    (let [pairs (map (fn [[block-id ^RegistryObject ro]]
                                       [block-id (.get ro)])
                                     ros)
                          block-insts (mapv second pairs)
                          block-id-by-inst (java.util.IdentityHashMap.)]
                      (doseq [[block-id inst] pairs]
                        (.put block-id-by-inst inst block-id))
                      (let [be-type (bootstrap/create-scripted-block-entity-type
                                      tile-id
                                      block-insts
                                      (reify java.util.function.Function
                                        (apply [_ block-inst]
                                          (.get block-id-by-inst block-inst))))]
                        be-type)))))]
          (swap! registered-block-entities assoc tile-id registered-obj))))))

(defn get-registered-block-entity-type
  "Get a registered BlockEntityType by tile-id or block-id."
  [tile-or-block-id]
  (let [tile-id (or (when (contains? @registered-block-entities tile-or-block-id)
                      tile-or-block-id)
                    (registry-metadata/get-block-tile-id tile-or-block-id))]
    (when-let [registered-obj (and tile-id (get @registered-block-entities tile-id))]
      (.get ^RegistryObject registered-obj))))

;; Dynamic item registration using metadata
(defn register-all-items!
  "Register all items defined in DSL using metadata-driven approach.
  Platform code does not know specific item names."
  []
  ;; Register standalone items
  (doseq [item-id (registry-metadata/get-all-item-ids)]
    (let [registry-name (registry-metadata/get-item-registry-name item-id)
          registered-obj (.register ^DeferredRegister (force items-register) registry-name
                          (reify java.util.function.Supplier
                            (get [_]
                              (Item. (Item$Properties.)))))]
      (swap! registered-items assoc item-id registered-obj)))
  
  ;; Register BlockItems for all blocks
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (when (and (registry-metadata/should-create-block-item? block-id)
               (not (registry-metadata/fluid-block? block-id)))
      (let [registry-name (registry-metadata/get-block-registry-name block-id)
            block-registered (get @registered-blocks block-id)
            registered-obj (.register ^DeferredRegister (force items-register) registry-name
                            (reify java.util.function.Supplier
                              (get [_]
                                (BlockItem. (.get ^RegistryObject block-registered) (Item$Properties.)))))]
        (swap! registered-items assoc (str block-id "-item") registered-obj)))))

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
  (when-let [registered-obj (get @registered-blocks block-id)]
    (.get ^RegistryObject registered-obj)))

(defn get-registered-item
  "Get a registered item by its DSL ID.
  
  Args:
    item-id: String - DSL item identifier (e.g., \"demo-item\")
  
  Returns:
    RegistryObject - The registered item, or nil if not found"
  [item-id]
  (when-let [registered-obj (get @registered-items item-id)]
    (.get ^RegistryObject registered-obj)))

(defn get-registered-block-item
  "Get a registered block item by its block ID.
  
  Args:
    block-id: String - DSL block identifier (e.g., \"demo-block\")
  
  Returns:
    RegistryObject - The registered block item, or nil if not found"
  [block-id]
  (when-let [registered-obj (get @registered-items (str block-id "-item"))]
    (.get ^RegistryObject registered-obj)))

(defn get-registered-fluid-source
  [fluid-id]
  (when-let [registered-obj (get @registered-fluids-source fluid-id)]
    (.get ^RegistryObject registered-obj)))

(defn get-registered-fluid-flowing
  [fluid-id]
  (when-let [registered-obj (get @registered-fluids-flowing fluid-id)]
    (.get ^RegistryObject registered-obj)))

(defn- build-creative-tab
  "Build the mod creative tab. displayItems callback runs lazily (when tab is opened),
  so registered-items/registered-blocks atoms are fully populated by then."
  []
  (-> (CreativeModeTab/builder)
      (.title (Component/translatable (str "itemGroup." mod-id ".items")))
      (.icon (reify java.util.function.Supplier
           (get [_]
             (try
               (.getDefaultInstance Items/BARRIER)
               (catch Exception _
                 (net.minecraft.world.item.ItemStack/EMPTY))))))
      (.displayItems (reify net.minecraft.world.item.CreativeModeTab$DisplayItemsGenerator
                       (accept [_ _params output]
                         (doseq [entry (registry-metadata/get-all-creative-tab-entries)]
                           (let [item-id (:id entry)
                                 item-obj (if (= (:type entry) :block-item)
                                            (get-registered-block-item item-id)
                                            (get-registered-item item-id))]
                             (when item-obj
                               (.accept output (net.minecraft.world.item.ItemStack. ^ItemLike item-obj))))))))
      (.build)))

;; ============================================================================
;; Setup Phase Helpers (must be defined before mod-init)
;; ============================================================================

;; Helper: Common setup phase (subscribed to FMLCommonSetupEvent in mod-init)
(defn on-common-setup [_event]
  (log/info "FMLCommonSetupEvent - Common setup phase")
  (gui-init/init-common!)
  (runtime-lifecycle/init-common!)
  ;; Initialize Forge Energy integration
  (forge-energy/init-forge-energy!)
  ;; Initialize IC2 integration (optional - no-op if IC2 not present)
  (ic2-energy/init-ic2-energy!)
  (runtime-item-handler/init!)
  ;; Register wireless IMC dispatch listeners on the Forge game event bus.
  (wireless-imc/init!)
  ;; Left-click block must be intercepted early to avoid client-side fake break effects
  ;; when ability mode blocks real breaking.
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false net.minecraftforge.event.entity.player.PlayerInteractEvent$LeftClickBlock
                (reify java.util.function.Consumer
                  (accept [_ evt]
                    (events/handle-left-click-block-event evt))))
  ;; Right-click block is handled by Java ForgeEventHandler (@SubscribeEvent).
  ;; Do not register it again here, otherwise one click is processed twice.
  ;; Block place events for multi-block overlap checks and :on-place handlers
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false net.minecraftforge.event.level.BlockEvent$EntityPlaceEvent
                (reify java.util.function.Consumer
                  (accept [_ evt]
                    (events/handle-block-place-event evt))))
  ;; Block break events for controller/part routed break logic
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false net.minecraftforge.event.level.BlockEvent$BreakEvent
                (reify java.util.function.Consumer
                  (accept [_ evt]
                    (events/handle-block-break-event evt)))))

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
          (when-let [install-i18n! (side/resolve-client-fn 'cn.li.forge1201.client.i18n-impl 'install-client-i18n!)]
            (install-i18n!))
          (if-let [init-client! (side/resolve-client-fn 'cn.li.forge1201.client.init 'init-client)]
            (init-client!)
            (log/error "Client-side detected but client init failed to load")))))))

;; ============================================================================
;; Constructor Implementation
;; ============================================================================

;; Constructor implementation
(defn mod-init []
  (let [aot? (aot-compilation?)
        cphant? (clojurephant-compilation?)
        check? (= "true" (System/getProperty "ac.check.clojure"))]
    (log/info "[BOOTSTRAP_TRACE] mod-init enter"
              {:aot aot?
               :clojurephant cphant?
               :ac-check check?
               :compile-files (boolean *compile-files*)})
    (if (or aot? cphant? check?)
      (do
        (log/warn "[BOOTSTRAP_TRACE] mod-init skip bootstrap-sensitive path"
                  {:reason {:aot aot? :clojurephant cphant? :ac-check check?}})
        [[] nil])
      (try
        (log/info "[BOOTSTRAP_TRACE] mod-init runtime path begin")
        ;; CRITICAL: Initialize platform abstractions FIRST
        ;; This must happen before any core code runs that uses NBT/BlockPos/World
        (platform-impl/init-platform!)
        ;; Core init (ac) sets *resource-location-fn* for mcmod gui.components/client.resources.
        (init/init-from-java)
        ;; Runtime content load is delay-backed in ac.core and explicitly activated here.
        (when-let [activate-content! (requiring-resolve 'cn.li.ac.core/activate-runtime-content!)]
          (log/info "[BOOTSTRAP_TRACE] activating runtime content")
          (activate-content!))

        ;; Initialize BlockState properties from Clojure metadata
        ;; Must happen before block registration so Property objects are ready
        (when-let [init-props! (requiring-resolve 'cn.li.forge1201.blockstate-properties/init-all-properties!)]
          (init-props!))

        ;; Register all blocks and items using metadata-driven approach
        ;; DSL systems are automatically initialized when namespaces load
        (register-scripted-tile-hooks!)
        (register-all-fluids!)
        (register-all-blocks!)
        (register-block-entities!)
        (register-all-items!)

        ;; Register creative tab (safe icon = BARRIER so no dependency on item registry order)
        (log/info "Registering Forge creative tab...")
        (.register ^DeferredRegister (force creative-tabs-register) "items"
                   (reify java.util.function.Supplier
                     (get [_] (build-creative-tab))))

        ;; Populate GUI DeferredRegister before it is registered with the bus.
        ;; Must happen here (during mod-init) 鈥?registries are locked by FMLCommonSetupEvent.
        (gui-registry-impl/register-menu-types!)

        ;; Register DeferredRegisters and lifecycle event listeners on mod event bus.
        (let [^IEventBus mod-bus (.getModEventBus (FMLJavaModLoadingContext/get))]
          (config-bridge/register-all! mod-bus)
          (ModEntities/register mod-bus)
          (ModSounds/register mod-bus)
          (.register ^DeferredRegister (force fluid-types-register) mod-bus)
          (.register ^DeferredRegister (force fluids-register) mod-bus)
          (.register ^DeferredRegister (force blocks-register) mod-bus)
          (.register ^DeferredRegister (force items-register) mod-bus)
          (.register ^DeferredRegister (force block-entities-register) mod-bus)
          (.register ^DeferredRegister (force creative-tabs-register) mod-bus)
          (.register ^DeferredRegister (force gui-registry-impl/menu-register) mod-bus)
          (.addListener mod-bus EventPriority/NORMAL false FMLCommonSetupEvent
                        (reify java.util.function.Consumer
                          (accept [_ event] (on-common-setup event))))
          (.addListener mod-bus EventPriority/NORMAL false FMLClientSetupEvent
                        (reify java.util.function.Consumer
                          (accept [_ event] (on-client-setup event))))

          (when (side/client-side?)
            (try
              (let [rk-class (Class/forName "net.minecraftforge.client.event.RegisterKeyMappingsEvent")]
                (.addListener mod-bus EventPriority/NORMAL false rk-class
                              (reify java.util.function.Consumer
                                (accept [_ event]
                                  (when-let [register-keys! (side/resolve-client-fn 'cn.li.forge1201.client.init 'register-key-mappings!)]
                                    (register-keys! event))))))
              (catch Exception e
                (log/error "Failed to register key mapping listener" e))))

          ;; Scripted BER: use @Mod.EventBusSubscriber Java class ModClientRenderSetup 鈥?          ;; addListener(modBus, Class, Consumer) for EntityRenderersEvent$RegisterRenderers
          ;; did not reliably dispatch from Clojure reify.
          (.addListener mod-bus EventPriority/NORMAL false InterModProcessEvent
                        (reify java.util.function.Consumer
                          (accept [_ event]
                            (let [^InterModProcessEvent event event
                                  ^java.util.stream.Stream imc-stream (.getIMCStream event)]
                              (doseq [^InterModComms$IMCMessage msg (iterator-seq (.iterator imc-stream))]
                                (try
                                  (let [handler (.get (.getMessageSupplier msg))]
                                    (condp = (.getMethod msg)
                                      WirelessImc/REGISTER_NETWORK_HANDLER
                                      (wireless-imc/register-network-handler! handler)
                                      WirelessImc/REGISTER_NODE_HANDLER
                                      (wireless-imc/register-node-handler! handler)
                                      nil))
                                  (catch Exception e
                                    (log/debug "IMC registration failed from"
                                               (.getSenderModId msg) ":" (ex-message e)))))))))
          (.addListener mod-bus EventPriority/NORMAL false RegisterCapabilitiesEvent
                        (reify java.util.function.Consumer
                          (accept [_ event]
                            (let [^RegisterCapabilitiesEvent event event]
                              (doseq [^Class java-type (distinct (keep (fn [[_key {:keys [java-type]}]]
                                                                        java-type)
                                                                      @platform-cap/capability-type-registry))]
                                (.register event java-type)))))))

        (log/info "[BOOTSTRAP_TRACE] mod-init runtime path end")
        [[] nil]
        (catch IllegalArgumentException e
          (let [msg (some-> e .getMessage str)]
            (if (and msg (.contains msg "Not bootstrapped"))
              (do
                (log/warn "Skipping Forge mod-init during checkClojure: Minecraft registries not bootstrapped")
                [[] nil])
              (throw e))))))))

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
