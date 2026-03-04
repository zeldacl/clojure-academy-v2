(ns my-mod.forge1201.mod
  "Forge 1.20.1 main mod class - generated with gen-class"
  (:require [my-mod.forge1201.init :as init]
            [my-mod.forge1201.registry :as registry]
            [my-mod.forge1201.events :as events]
            [my-mod.forge1201.gui.impl :as gui]
            [my-mod.forge1201.gui.init :as gui-init]
            [my-mod.forge1201.platform-impl :as platform-impl]
            [my-mod.block.dsl :as bdsl]
            [my-mod.item.dsl :as idsl]
            [my-mod.registry.metadata :as registry-metadata]
            [my-mod.config.modid :as modid]
            [my-mod.util.log :as log])
  (:import [net.minecraft.world.level.block Block Blocks]
           [net.minecraft.world.level.block.state BlockBehaviour BlockBehaviour$Properties]
           [net.minecraft.world.item Item Item$Properties BlockItem]
           [net.minecraftforge.fml.common Mod]
           [net.minecraftforge.fml.javafmlmod FMLJavaModLoadingContext]
           [net.minecraftforge.fml.event.lifecycle FMLCommonSetupEvent FMLClientSetupEvent]
           [net.minecraftforge.registries DeferredRegister ForgeRegistries]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock])
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

;; Storage for registered blocks and items (populated during initialization)
(defonce registered-blocks (atom {}))
(defonce registered-items (atom {}))

;; Dynamic block registration using metadata
(defn register-all-blocks!
  "Register all blocks defined in DSL using metadata-driven approach.
  Platform code does not know specific block names."
  []
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (let [registry-name (registry-metadata/get-block-registry-name block-id)
          block-spec (registry-metadata/get-block-spec block-id)
          registered-obj (.register blocks-register registry-name
                          (reify java.util.function.Supplier
                            (get [_]
                              (Block. (BlockBehaviour$Properties/copy Blocks/STONE)))))]
      (swap! registered-blocks assoc block-id registered-obj))))

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

;; Constructor implementation
(defn mod-init []
  (log/info "Initializing MyMod1201 from Clojure...")
  
  ;; CRITICAL: Initialize platform abstractions FIRST
  ;; This must happen before any core code runs that uses NBT/BlockPos/World
  (platform-impl/init-platform!)
  
  ;; Register all blocks and items using metadata-driven approach
  ;; DSL systems are automatically initialized when namespaces load
  (register-all-blocks!)
  (register-all-items!)
  
  (let [mod-bus (.getModEventBus (FMLJavaModLoadingContext/get))
        this-obj (com.example.my_mod1201.MyMod1201Clj.)]
    ;; Register DeferredRegisters
    (.register blocks-register mod-bus)
    (.register items-register mod-bus)
    
    ;; Add setup listeners using method references (Forge 1.20.1 compatible)
    (.addListener mod-bus FMLCommonSetupEvent
      (reify java.util.function.Consumer
        (accept [_ event]
          (.commonSetup this-obj event))))

    ;; Add client setup listener
    (.addListener mod-bus FMLClientSetupEvent
      (reify java.util.function.Consumer
        (accept [_ event]
          (.clientSetup this-obj event)))))
  
  ;; Register to gameplay event bus (using gen-class method for SubscribeEvent pattern)
  ;; Note: Forge 1.20.1 event registration is handled via EventBusSubscriber or manual registration
  ;; For now we'll use the manual approach in init
  
  ;; Initialize Clojure adapters
  (init/init-from-java)
  
  ;; Return state
  [[] nil])

;; Common setup method implementation (called on both client and server)
(defn mod-commonSetup [this event]
  (log/info "FMLCommonSetupEvent - Common setup phase")
  ;; Common initialization (runs on both client and server)
  (gui-init/init-common!)
  
  ;; Register gameplay event listeners
  ;; In Forge 1.20.1, we need to use the EVENT_BUS with proper type registration
  (.addListener (MinecraftForge/EVENT_BUS) PlayerInteractEvent$RightClickBlock
    (reify java.util.function.Consumer
      (accept [_ evt]
        (events/handle-right-click-event evt)))))

;; Client setup method implementation (called only on client)
(defn mod-clientSetup [this event]
  (log/info "FMLClientSetupEvent - Client setup phase")
  ;; Client-only initialization
  (gui-init/init-client!)
  (if-let [init-client! (requiring-resolve 'my-mod.forge1201.client.init/init-client)]
    (init-client!)
    (log/warn "Forge client init namespace unavailable on current side")))

;; Event handler method (required by gen-class, but not used directly in 1.20.1)
(defn mod-onRightClickBlock [this event]
  (events/handle-right-click-event event))

;; Generic helpers to query registered blocks/items by ID
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
