(ns my-mod.fabric1201.mod
  "Fabric 1.20.1 main mod initialization"
  (:require [my-mod.fabric1201.init :as init]
            [my-mod.fabric1201.datagen.setup :as datagen]
            [my-mod.fabric1201.registry :as registry]
            [my-mod.fabric1201.events :as events]
            [my-mod.fabric1201.gui.impl :as gui]
            [my-mod.fabric1201.platform-impl :as platform-impl]
            [my-mod.block.dsl :as bdsl]
            [my-mod.block.blockstate-properties :as bsp]
            [my-mod.item.dsl :as idsl]
            [my-mod.registry.metadata :as registry-metadata]
            [my-mod.config.modid :as modid]
            [my-mod.util.log :as log])
  (:import [net.minecraft.core Registry]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.item Item BlockItem CreativeModeTabs]
           [net.minecraft.world.level.block Block Blocks]
           [net.minecraft.world.level.block.state BlockBehaviour]
           [net.fabricmc.fabric.api.itemgroup.v1 FabricItemGroup ItemGroupEvents]
           [net.minecraft.network.chat Component]
           [my_mod.block NodeDynamicBlock]))

;; Mod ID constant
(def mod-id modid/MOD-ID)

;; Storage for registered blocks and items (populated during initialization)
(defonce registered-blocks (atom {}))
(defonce registered-items (atom {}))

(defn register-blocks []
  "Register all blocks using metadata-driven approach.
  Platform code does not know specific block names."
  (log/info "Registering Fabric blocks...")
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (let [registry-name (registry-metadata/get-block-registry-name block-id)
          block-spec (registry-metadata/get-block-spec block-id)
          ;; Query business layer for block state properties
          needs-dynamic-properties? (registry-metadata/has-block-state-properties? block-id)
          block-obj (if needs-dynamic-properties?
                      ;; Create NodeDynamicBlock with dynamic BlockState properties.
                      ;; Properties are injected during construction via the static
                      ;; factory to ensure they are visible when the Block's
                      ;; StateDefinition is built.
                      (let [props (bsp/get-all-properties block-id)]
                        (NodeDynamicBlock/create block-id
                                                 (java.util.ArrayList. props)
                                                 (BlockBehaviour$Properties/copy Blocks/STONE)))
                      ;; Use standard Block for simple blocks
                      (Block. (BlockBehaviour$Properties/copy Blocks/STONE)))]
      (registry/register-block registry-name block-obj)
      (swap! registered-blocks assoc block-id block-obj))))

(defn register-items []
  "Register all items using metadata-driven approach.
  Platform code does not know specific item names."
  (log/info "Registering Fabric items...")
  ;; Register standalone items
  (doseq [item-id (registry-metadata/get-all-item-ids)]
    (let [registry-name (registry-metadata/get-item-registry-name item-id)
          item-spec (registry-metadata/get-item-spec item-id)
          item-obj (Item. (Item$Properties.))]
      (registry/register-item registry-name item-obj)
      (swap! registered-items assoc item-id item-obj)))
  
  ;; Register BlockItems for all blocks
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (when (registry-metadata/should-create-block-item? block-id)
      (let [registry-name (registry-metadata/get-block-registry-name block-id)
            block-obj (get @registered-blocks block-id)
            block-item-obj (BlockItem. block-obj (.. (Item$Properties.) (stacksTo 64)))]
        (registry/register-item registry-name block-item-obj)
        (swap! registered-items assoc (str block-id "-item") block-item-obj)))))

;; Creative Tab storage
(defonce creative-tab (atom nil))

(defn register-creative-tab []
  "Register custom creative mode tab for all mod items.
  Uses metadata-driven approach to populate tab contents."
  (log/info "Registering Fabric creative tab...")
  
  ;; Create custom creative tab using FabricItemGroup
  (let [tab-id (ResourceLocation. mod-id "items")
        tab (-> (FabricItemGroup/builder)
                (.icon (fn []
                         ;; Use first available item as icon, fallback to barrier
                         (let [entries (registry-metadata/get-all-creative-tab-entries)]
                           (if-let [first-entry (first entries)]
                             (let [item-name (:registry-name first-entry)
                                   item-obj (get @registered-items (:id first-entry))]
                               (if item-obj
                                 (.getDefaultInstance item-obj)
                                 (.getDefaultInstance net.minecraft.world.item.Items/BARRIER)))
                             (.getDefaultInstance net.minecraft.world.item.Items/BARRIER)))))
                (.title (Component/translatable (str "itemGroup." mod-id ".items")))
                (.build))]
    
    ;; Register the tab
    (Registry/register BuiltInRegistries/CREATIVE_MODE_TAB tab-id tab)
    (reset! creative-tab tab)
    (log/info "Creative tab registered:" tab-id)
    
    ;; Populate tab with all items and block items
    (ItemGroupEvents/modifyEntriesEvent tab
      (reify java.util.function.Consumer
        (accept [_ entries]
          (log/info "Populating creative tab with mod items...")
          (doseq [entry (registry-metadata/get-all-creative-tab-entries)]
            (let [item-id (:id entry)
                  item-obj (if (= (:type entry) :block-item)
                             (get @registered-items (str item-id "-item"))
                             (get @registered-items item-id))]
              (when item-obj
                (.accept entries item-obj)
                (log/debug "Added to creative tab:" item-id)))))))))

(defn mod-init []
  "Main mod initialization called from Java ModInitializer"
  (log/info "Initializing MyMod (Fabric 1.20.1) from Clojure...")
  
  ;; CRITICAL: Initialize platform abstractions FIRST
  ;; This must happen before any core code runs that uses NBT/BlockPos/World
  (platform-impl/init-platform!)
  
  ;; Initialize Clojure adapters
  (init/init-from-java)
  
  ;; Initialize BlockState properties from Clojure metadata
  ;; Must happen before block registration so Property objects are ready
  (bsp/init-all-properties!)
  
  ;; Register blocks and items using metadata-driven approach
  ;; DSL systems are automatically initialized when namespaces load
  (register-blocks)
  (register-items)
  
  ;; Register creative mode tab and populate with all items
  (register-creative-tab)
  
  ;; Register event listeners
  (events/register-events)
  
  (log/info "MyMod (Fabric 1.20.1) initialization complete"))

;; Generic helpers to query registered blocks/items by ID
(defn get-registered-block
  "Get a registered block by its DSL ID.
  
  Args:
    block-id: String - DSL block identifier (e.g., \"demo-block\")
  
  Returns:
    Block - The registered block, or nil if not found"
  [block-id]
  (get @registered-blocks block-id))

(defn get-registered-item
  "Get a registered item by its DSL ID.
  
  Args:
    item-id: String - DSL item identifier (e.g., \"demo-item\")
  
  Returns:
    Item - The registered item, or nil if not found"
  [item-id]
  (get @registered-items item-id))

(defn get-registered-block-item
  "Get a registered block item by its block ID.
  
  Args:
    block-id: String - DSL block identifier (e.g., \"demo-block\")
  
  Returns:
    BlockItem - The registered block item, or nil if not found"
  [block-id]
  (get @registered-items (str block-id "-item")))
