(ns cn.li.forge1201.mod
  "Forge 1.20.1 main mod class - generated with gen-class"
  (:require [cn.li.forge1201.init :as init]
            [cn.li.forge1201.registry :as registry]
            [cn.li.forge1201.events :as events]
            [cn.li.forge1201.gui.impl :as gui]
            [cn.li.forge1201.gui.init :as gui-init]
            [cn.li.forge1201.gui.registry-impl :as gui-registry-impl]
            ;; platform-impl 会在 runtime 的 mod-init 中按需加载（避免 AOT/checkClojure 阶段触发 Minecraft class init）
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.forge1201.blockstate-properties :as bsp]
            [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.world.level.block Block]
           [cn.li.forge1201.block.entity ScriptedBlockEntity]
           [net.minecraft.world.item Item Item$Properties BlockItem CreativeModeTab]
           [net.minecraft.network.chat Component]
           [net.minecraftforge.fml.javafmlmod FMLJavaModLoadingContext]
           [net.minecraftforge.fml.event.lifecycle FMLCommonSetupEvent FMLClientSetupEvent]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock]
           [net.minecraftforge.common.capabilities RegisterCapabilitiesEvent])
  (:gen-class
   :name com.example.my_mod1201.MyMod1201Clj
   :prefix "mod-"
   :init init
   :state state
   :constructors {[] []}
   :methods [[onRightClickBlock [net.minecraftforge.event.entity.player.PlayerInteractEvent$RightClickBlock] void]
             [commonSetup [net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent] void]
             [clientSetup [net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent] void]]))

;; Mod ID constant
(def mod-id modid/*mod-id*)

(defn- invoke-bootstrap-helper
  [method-name & args]
  (clojure.lang.Reflector/invokeStaticMethod
    "cn.li.forge1201.shim.ForgeBootstrapHelper"
    method-name
    (to-array args)))

;; DeferredRegister instances
(defonce blocks-register
  ;; AOT/checkClojure 阶段 Minecraft registries 尚未 bootstrapped。
  ;; 延迟创建，避免触发 Bootstrap。
  (delay (invoke-bootstrap-helper "createBlocksRegister" mod-id)))

(defonce items-register
  (delay (invoke-bootstrap-helper "createItemsRegister" mod-id)))

(defonce creative-tabs-register
  (delay (invoke-bootstrap-helper "createCreativeTabsRegister" mod-id)))

;; BlockEntity types
(defonce block-entities-register
  (delay (invoke-bootstrap-helper "createBlockEntityTypesRegister" mod-id)))

;; Storage for registered blocks and items (populated during initialization)
(defonce registered-blocks (atom {}))
(defonce registered-items (atom {}))
(defonce registered-block-entities (atom {}))

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
          needs-dynamic-properties? (has-block-state-properties? block-id)
          has-be? (registry-metadata/has-block-entity? block-id)
          tile-id (when has-be?
                    (or (registry-metadata/get-block-tile-id block-id) block-id))
          registered-obj (.register (force blocks-register) registry-name
                                    (reify java.util.function.Supplier
                                      (get [_]
                                        (cond
                                          (and needs-dynamic-properties? has-be?)
                                          (let [props (bsp/get-all-properties block-id)]
                                            (invoke-bootstrap-helper "createCarrierScriptedDynamicBlock" block-id tile-id props))
                                          needs-dynamic-properties?
                                          (let [props (bsp/get-all-properties block-id)]
                                            (invoke-bootstrap-helper "createDynamicStateBlock" block-id props))
                                          has-be?
                                          (invoke-bootstrap-helper "createCarrierScriptedBlock" block-id tile-id)
                                          :else
                                          (invoke-bootstrap-helper "createPlainBlock")))))]
      (swap! registered-blocks assoc block-id registered-obj))))

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
                (force block-entities-register)
                registry-name
                (reify java.util.function.Supplier
                  (get [_]
                    ;; Resolve RegistryObjects to Blocks at registration time
                    (let [pairs (map (fn [[block-id ro]]
                                       [block-id (.get ro)])
                                     ros)
                          block-insts (mapv second pairs)
                          block-id-by-inst (java.util.IdentityHashMap.)]
                      (doseq [[block-id inst] pairs]
                        (.put block-id-by-inst inst block-id))
                      (let [be-type (invoke-bootstrap-helper
                                      "createScriptedBlockEntityType"
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
      (.get registered-obj))))

;; Dynamic item registration using metadata
(defn register-all-items!
  "Register all items defined in DSL using metadata-driven approach.
  Platform code does not know specific item names."
  []
  ;; Register standalone items
  (doseq [item-id (registry-metadata/get-all-item-ids)]
    (let [registry-name (registry-metadata/get-item-registry-name item-id)
          item-spec (registry-metadata/get-item-spec item-id)
          registered-obj (.register (force items-register) registry-name
                          (reify java.util.function.Supplier
                            (get [_]
                              (Item. (Item$Properties.)))))]
      (swap! registered-items assoc item-id registered-obj)))
  
  ;; Register BlockItems for all blocks
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (when (registry-metadata/should-create-block-item? block-id)
      (let [registry-name (registry-metadata/get-block-registry-name block-id)
            block-registered (get @registered-blocks block-id)
            registered-obj (.register (force items-register) registry-name
                            (reify java.util.function.Supplier
                              (get [_]
                                (BlockItem. (.get block-registered) (Item$Properties.)))))]
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
    (.get registered-obj)))

(defn get-registered-item
  "Get a registered item by its DSL ID.
  
  Args:
    item-id: String - DSL item identifier (e.g., \"demo-item\")
  
  Returns:
    RegistryObject - The registered item, or nil if not found"
  [item-id]
  (when-let [registered-obj (get @registered-items item-id)]
    (.get registered-obj)))

(defn get-registered-block-item
  "Get a registered block item by its block ID.
  
  Args:
    block-id: String - DSL block identifier (e.g., \"demo-block\")
  
  Returns:
    RegistryObject - The registered block item, or nil if not found"
  [block-id]
  (when-let [registered-obj (get @registered-items (str block-id "-item"))]
    (.get registered-obj)))

(defn- build-creative-tab []
  "Build the mod creative tab. displayItems callback runs lazily (when tab is opened),
  so registered-items/registered-blocks atoms are fully populated by then."
  (-> (CreativeModeTab/builder)
      (.title (Component/translatable (str "itemGroup." mod-id ".items")))
      (.icon (reify java.util.function.Supplier
           (get [_] (.getDefaultInstance (invoke-bootstrap-helper "barrierItem")))))
      (.displayItems (reify net.minecraft.world.item.CreativeModeTab$DisplayItemsGenerator
                       (accept [_ _params output]
                         (doseq [entry (registry-metadata/get-all-creative-tab-entries)]
                           (let [item-id (:id entry)
                                 item-obj (if (= (:type entry) :block-item)
                                            (get-registered-block-item item-id)
                                            (get-registered-item item-id))]
                             (when item-obj
                               (.accept output (net.minecraft.world.item.ItemStack. item-obj 1))))))))
      (.build)))

;; ============================================================================
;; Setup Phase Helpers (must be defined before mod-init)
;; ============================================================================

;; Helper: Common setup phase (subscribed to FMLCommonSetupEvent in mod-init)
(defn on-common-setup [_event]
  (log/info "FMLCommonSetupEvent - Common setup phase")
  (gui-init/init-common!)
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false PlayerInteractEvent$RightClickBlock
                (reify java.util.function.Consumer
                  (accept [_ evt]
                    (events/handle-right-click-event evt))))
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
(defn on-client-setup [event]
  (log/info "FMLClientSetupEvent - Client setup phase")
  ;; Client-only initialization
  (gui-init/init-client!)
  ;; Install platform i18n implementation for shared code (client-side only).
  (alter-var-root #'i18n/*translate-fn*
                  (constantly (fn [k]
                                (try
                                  (net.minecraft.client.resources.language.I18n/get (str k) (object-array 0))
                                  (catch Throwable _ (str k))))))
  (if-let [init-client! (requiring-resolve 'cn.li.forge1201.client.init/init-client)]
    (init-client!)
    (log/warn "Forge client init namespace unavailable on current side")))

;; ============================================================================
;; Constructor Implementation
;; ============================================================================

;; Constructor implementation
(defn mod-init []
  (log/info "Initializing MyMod1201 from Clojure...")
  (try
    ;; CRITICAL: Initialize platform abstractions FIRST
    ;; This must happen before any core code runs that uses NBT/BlockPos/World
    (when-let [init-platform! (requiring-resolve 'cn.li.forge1201.platform-impl/init-platform!)]
      (init-platform!))
    ;; Core init (ac) sets *resource-location-fn* for mcmod gui.components/client.resources
    ;; ac namespaces load here; deftile-kind / declare-capability! calls execute at this point
    (init/init-from-java)

    ;; Initialize BlockState properties from Clojure metadata
    ;; Must happen before block registration so Property objects are ready
    (bsp/init-all-properties!)

    ;; Register all blocks and items using metadata-driven approach
    ;; DSL systems are automatically initialized when namespaces load
    (register-scripted-tile-hooks!)
    (register-all-blocks!)
    (register-block-entities!)
    (register-all-items!)
    
    ;; Register creative tab (safe icon = BARRIER so no dependency on item registry order)
    (log/info "Registering Forge creative tab...")
    (.register (force creative-tabs-register) "items"
               (reify java.util.function.Supplier
                 (get [_] (build-creative-tab))))

    ;; Populate GUI DeferredRegister before it is registered with the bus.
    ;; Must happen here (during mod-init) — registries are locked by FMLCommonSetupEvent.
    (gui-registry-impl/register-menu-types!)

    ;; Register DeferredRegisters and lifecycle event listeners on mod event bus.
    ;; Must use addListener(EventPriority, boolean, Class<T>, Consumer<T>) overload:
    ;; Clojure's reify erases generic type info, so Forge cannot infer the event
    ;; type from the Consumer alone.
    (let [mod-bus (.getModEventBus (FMLJavaModLoadingContext/get))]
      (.register (force blocks-register) mod-bus)
      (.register (force items-register) mod-bus)
      (.register (force block-entities-register) mod-bus)
      (.register (force creative-tabs-register) mod-bus)
      (.register (force gui-registry-impl/menu-register) mod-bus)
      (.addListener mod-bus EventPriority/NORMAL false FMLCommonSetupEvent
                    (reify java.util.function.Consumer
                      (accept [_ event] (on-common-setup event))))
      (.addListener mod-bus EventPriority/NORMAL false FMLClientSetupEvent
                    (reify java.util.function.Consumer
                      (accept [_ event] (on-client-setup event))))
      ;; Generic RegisterCapabilitiesEvent: registers all java-types declared by ac.
      ;; No modification needed when ac adds new capabilities.
      (.addListener mod-bus EventPriority/NORMAL false RegisterCapabilitiesEvent
                    (reify java.util.function.Consumer
                      (accept [_ event]
                        (doseq [[_key {:keys [java-type]}] @platform-cap/capability-type-registry]
                          (when java-type
                            (.register event java-type)))))))
    
    ;; Return state
    [[] nil]
    (catch IllegalArgumentException e
      (let [msg (some-> e .getMessage str)]
        (if (and msg (.contains msg "Not bootstrapped"))
          (do
            (log/warn "Skipping Forge mod-init during checkClojure: Minecraft registries not bootstrapped")
            [[] nil])
          (throw e))))))

;; (defn start-repl-safe []
;;   (let [cl (.getContextClassLoader (Thread/currentThread))]
;;     (nrepl/start-server :port 7888 :handler (nrepl/default-handler))
;;     ;; 确保 REPL 线程能访问到 Minecraft 的类
;;     (.setContextClassLoader (Thread/currentThread) cl)))

;; ============================================================================
;; Gen-class Method Implementations
;; ============================================================================

;; Gen-class method implementations (required by gen-class contract)
(defn mod-commonSetup [this event]
  (on-common-setup event))

(defn mod-clientSetup [this event]
  (on-client-setup event))

;; Event handler method (required by gen-class, but not used directly in 1.20.1)
(defn mod-onRightClickBlock [this event]
  (events/handle-right-click-event event))
