(ns my-mod.forge1201.mod
  "Forge 1.20.1 main mod class - generated with gen-class"
  (:require [my-mod.forge1201.init :as init]
            [my-mod.forge1201.registry :as registry]
            [my-mod.forge1201.events :as events]
            [my-mod.forge1201.gui.impl :as gui]
            [my-mod.forge1201.gui.init :as gui-init]
            [my-mod.forge1201.platform-impl :as platform-impl]
            [my-mod.block.dsl :as bdsl]
            [my-mod.block.wireless-node]
            [my-mod.block.wireless-matrix]
            [my-mod.block.tile-logic :as tile-logic]
            [my-mod.block.blockstate-properties :as bsp]
            [my-mod.item.dsl :as idsl]
            [my-mod.registry.metadata :as registry-metadata]
            [my-mod.config.modid :as modid]
            [my-mod.util.log :as log])
  (:import [net.minecraft.world.level.block Block Blocks]
           [net.minecraft.world.level.block.state BlockBehaviour BlockBehaviour$Properties]
           [my_mod.block ScriptedBlock DynamicStateBlock]
           [my_mod.block.entity ScriptedBlockEntity]
           [net.minecraft.world.item Item Item$Properties BlockItem CreativeModeTab CreativeModeTabs]
           [net.minecraft.world.level.block.entity BlockEntityType BlockEntityType$Builder BlockEntityType$BlockEntitySupplier]
           [net.minecraft.core.registries Registries]
           [net.minecraft.network.chat Component]
           [net.minecraftforge.fml.common Mod]
           [net.minecraftforge.fml.javafmlmod FMLJavaModLoadingContext]
           [net.minecraftforge.fml.event.lifecycle FMLCommonSetupEvent FMLClientSetupEvent]
           [net.minecraftforge.registries DeferredRegister ForgeRegistries]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock]
           [net.minecraftforge.event BuildCreativeModeTabContentsEvent])
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
(def mod-id modid/MOD-ID)

;; DeferredRegister instances
(defonce blocks-register 
  (DeferredRegister/create ForgeRegistries/BLOCKS mod-id))

(defonce items-register 
  (DeferredRegister/create ForgeRegistries/ITEMS mod-id))

(defonce creative-tabs-register
  (DeferredRegister/create Registries/CREATIVE_MODE_TAB mod-id))

;; BlockEntity types
(defonce block-entities-register
  (DeferredRegister/create ForgeRegistries/BLOCK_ENTITY_TYPES mod-id))

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
          block-spec (registry-metadata/get-block-spec block-id)
          needs-dynamic-properties? (has-block-state-properties? block-id)
          has-be? (registry-metadata/has-block-entity? block-id)
          tile-id (when has-be?
                    (or (registry-metadata/get-block-tile-id block-id) block-id))
          registered-obj (.register blocks-register registry-name
                          (reify java.util.function.Supplier
                            (get [_]
                              (cond
                                (and needs-dynamic-properties? has-be?)
                                (let [props (bsp/get-all-properties block-id)]
                                  (ScriptedBlock/create block-id
                                                        tile-id
                                                        (java.util.ArrayList. props)
                                                        (BlockBehaviour$Properties/copy Blocks/STONE)))
                                needs-dynamic-properties?
                                (let [props (bsp/get-all-properties block-id)]
                                  (DynamicStateBlock/create block-id
                                                            (java.util.ArrayList. props)
                                                            (BlockBehaviour$Properties/copy Blocks/STONE)))
                                has-be?
                                (ScriptedBlock. block-id tile-id (BlockBehaviour$Properties/copy Blocks/STONE))
                                :else
                                (Block. (BlockBehaviour$Properties/copy Blocks/STONE))))))]
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
                block-entities-register
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
                      (let [type-holder (object-array 1)
                            be-type (let [supplier
                                          (reify BlockEntityType$BlockEntitySupplier
                                            (create [_ pos state]
                                              (let [block-inst (.getBlock state)
                                                    block-id (or (.get block-id-by-inst block-inst)
                                                                 tile-id)]
                                                (ScriptedBlockEntity. (aget type-holder 0) pos state tile-id block-id))))]
                                      (-> (BlockEntityType$Builder/of supplier (into-array Block block-insts))
                                          (.build nil)))]
                        (aset type-holder 0 be-type)
                        (ScriptedBlockEntity/registerType tile-id be-type)
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
          registered-obj (.register items-register registry-name
                          (reify java.util.function.Supplier
                            (get [_]
                              (Item. (Item$Properties.)))))]
      (swap! registered-items assoc item-id registered-obj)))
  
  ;; Register BlockItems for all blocks
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (when (registry-metadata/should-create-block-item? block-id)
      (let [registry-name (registry-metadata/get-block-registry-name block-id)
            block-registered (get @registered-blocks block-id)
            registered-obj (.register items-register registry-name
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

;; Creative Tab registration
(defn register-creative-tab!
  "Register custom creative mode tab for all mod items.
  Uses metadata-driven approach to populate tab contents."
  []
  (log/info "Registering Forge creative tab...")
  (.register creative-tabs-register "items"
             (reify java.util.function.Supplier
               (get [_]
                 (-> (CreativeModeTab/builder)
                     (.title (Component/translatable (str "itemGroup." mod-id ".items")))
                     (.icon (reify java.util.function.Supplier
                              (get [_]
                                ;; Use first available item as icon, fallback to barrier
                                (let [entries (registry-metadata/get-all-creative-tab-entries)]
                                  (if-let [first-entry (first entries)]
                                    (let [item-id (:id first-entry)
                                          item-obj (if (= (:type first-entry) :block-item)
                                                     (get-registered-block-item item-id)
                                                     (get-registered-item item-id))]
                                      (if item-obj
                                        (.getDefaultInstance item-obj)
                                        (.getDefaultInstance net.minecraft.world.item.Items/BARRIER)))
                                    (.getDefaultInstance net.minecraft.world.item.Items/BARRIER))))))
                     (.displayItems (reify net.minecraft.world.item.CreativeModeTab$DisplayItemsGenerator
                                      (accept [_ params output]
                                        ;; This will be called when the tab is opened
                                        ;; We'll populate it via BuildCreativeModeTabContentsEvent instead
                                        nil)))
                     (.build))))))

;; ============================================================================
;; Setup Phase Helpers (must be defined before mod-init)
;; ============================================================================

;; Helper: Common setup phase (called from event handler)
(defn on-common-setup [event]
  (log/info "FMLCommonSetupEvent - Common setup phase")
  ;; Common initialization (runs on both client and server)
  (gui-init/init-common!)

  ;; Register gameplay event listeners  
  ;; Note: Consumer.accept(Object) will receive the event from the bus
  (let [listener (reify java.util.function.Consumer
                   (accept [_ evt]
                     (events/handle-right-click-event evt)))]
    (.addListener (MinecraftForge/EVENT_BUS) listener))
  
  ;; Register creative tab content population listener
  (let [tab-listener (reify java.util.function.Consumer
                       (accept [_ evt]
                         (log/info "Populating creative tab with mod items...")
                         (doseq [entry (registry-metadata/get-all-creative-tab-entries)]
                           (let [item-id (:id entry)
                                 item-obj (if (= (:type entry) :block-item)
                                            (get-registered-block-item item-id)
                                            (get-registered-item item-id))]
                             (when item-obj
                               (.accept (.getEntries evt) item-obj)
                               (log/debug "Added to creative tab:" item-id))))))]
    (.addListener (MinecraftForge/EVENT_BUS) BuildCreativeModeTabContentsEvent tab-listener)))

;; Helper: Client setup phase (called from event handler)  
(defn on-client-setup [event]
  (log/info "FMLClientSetupEvent - Client setup phase")
  ;; Client-only initialization
  (gui-init/init-client!)
  (if-let [init-client! (requiring-resolve 'my-mod.forge1201.client.init/init-client)]
    (init-client!)
    (log/warn "Forge client init namespace unavailable on current side")))

;; ============================================================================
;; Constructor Implementation
;; ============================================================================

;; Constructor implementation
(defn mod-init []
  (log/info "Initializing MyMod1201 from Clojure...")
  
  ;; CRITICAL: Initialize platform abstractions FIRST
  ;; This must happen before any core code runs that uses NBT/BlockPos/World
  (platform-impl/init-platform!)
  
  ;; Initialize BlockState properties from Clojure metadata
  ;; Must happen before block registration so Property objects are ready
  (bsp/init-all-properties!)
  
  ;; Ensure tile-kind defaults are registered before scripted tile hooks.
  ;; This is a safety net in case tile-kind registration via Tile DSL has
  ;; not yet populated the core tile-logic registry.
  (tile-logic/register-tile-kind!
    :wireless-node
    {:tick-fn my-mod.block.wireless-node/node-scripted-tick-fn
     :read-nbt-fn my-mod.block.wireless-node/node-scripted-load-fn
     :write-nbt-fn my-mod.block.wireless-node/node-scripted-save-fn})

  (tile-logic/register-tile-kind!
    :wireless-matrix
    {:tick-fn my-mod.block.wireless-matrix/matrix-scripted-tick-fn
     :read-nbt-fn my-mod.block.wireless-matrix/matrix-scripted-load-fn
     :write-nbt-fn my-mod.block.wireless-matrix/matrix-scripted-save-fn})

  ;; Register all blocks and items using metadata-driven approach
  ;; DSL systems are automatically initialized when namespaces load
  (register-scripted-tile-hooks!)
  (register-all-blocks!)
  (register-block-entities!)
  (register-all-items!)
  
  ;; Register creative mode tab
  (register-creative-tab!)
  
  ;; Register DeferredRegisters with mod event bus
  (let [mod-bus (.getModEventBus (FMLJavaModLoadingContext/get))]
    (.register blocks-register mod-bus)
    (.register items-register mod-bus)
    (.register block-entities-register mod-bus)
    (.register creative-tabs-register mod-bus))
  
  ;; Setup phase event listeners
  ;; Note: Forge 1.20.1 event bus has specific requirements for type resolution
  ;; We'll invoke setup functions through the generated methods instead
  ;; The gen-class methods (commonSetup, clientSetup) will be called by Forge automatically
  ;; through the lifecycle events
  
  ;; Initialize Clojure adapters
  (init/init-from-java)
  
  ;; Return state
  [[] nil])

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
