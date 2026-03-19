 
  (ns cn.li.fabric1201.mod
    (:require [cn.li.mcmod.core :as core]
              [cn.li.fabric1201.init :as init]
              [cn.li.fabric1201.datagen.setup :as datagen]
              [cn.li.fabric1201.registry :as registry]
              [cn.li.fabric1201.events :as events]
              [cn.li.fabric1201.gui.impl :as gui]
              [cn.li.fabric1201.gui.init :as gui-init]
              [cn.li.fabric1201.gui.registry-impl :as gui-registry-impl]
              [cn.li.fabric1201.platform-impl :as platform-impl]
              [cn.li.mcmod.block.dsl :as bdsl]
              [cn.li.mcmod.block.tile-logic :as tile-logic]
              [cn.li.fabric1201.blockstate-properties :as bsp]
              [cn.li.mcmod.item.dsl :as idsl]
              [cn.li.mcmod.registry.metadata :as registry-metadata]
              [cn.li.mcmod.config.modid :as modid]
              [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.core Registry]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.item Item BlockItem CreativeModeTabs]
           [net.minecraft.world.level.block Block Blocks]
           [net.minecraft.world.level.block.state BlockBehaviour]
           [net.minecraft.world.level.block.entity BlockEntityType BlockEntityType$Builder BlockEntityType$BlockEntitySupplier]
           [net.fabricmc.fabric.api.itemgroup.v1 FabricItemGroup ItemGroupEvents]
           [net.minecraft.network.chat Component]
           [my_mod.block NodeDynamicBlock ScriptedEntityBlock ScriptedDynamicEntityBlock]
           [my_mod.block.entity ScriptedBlockEntity]))

;; Mod ID constant
(def mod-id modid/MOD-ID)

;; Storage for registered blocks and items (populated during initialization)
(defonce registered-blocks (atom {}))
(defonce registered-items (atom {}))
(defonce registered-block-entities (atom {}))

(defn- carrier-block-props
  "Properties for invisible scripted carrier blocks used by BER/TESR rendering.
  Keep them non-occluding and non-view-blocking so they do not darken blocks below."
  []
  (let [always-false (reify net.minecraft.world.level.block.state.BlockBehaviour$StatePredicate
                       (test [_ _state _level _pos] false))]
    (doto (BlockBehaviour$Properties/copy Blocks/STONE)
      (.noOcclusion)
      (.forceSolidOff)
      (.isViewBlocking always-false)
      (.isSuffocating always-false)
      (.isRedstoneConductor always-false))))

(defn register-blocks []
  "Register all blocks using metadata-driven approach.
  Platform code does not know specific block names."
  (log/info "Registering Fabric blocks...")
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (let [registry-name (registry-metadata/get-block-registry-name block-id)
          needs-dynamic-properties? (registry-metadata/has-block-state-properties? block-id)
          has-be? (registry-metadata/has-block-entity? block-id)
          tile-id (when has-be?
                    (or (registry-metadata/get-block-tile-id block-id) block-id))
          block-obj (cond
                      (and needs-dynamic-properties? has-be?)
                      (let [props (bsp/get-all-properties block-id)]
                        (ScriptedDynamicEntityBlock/create block-id
                                                           tile-id
                                                           (java.util.ArrayList. props)
                                                           (carrier-block-props)))
                      needs-dynamic-properties?
                      (let [props (bsp/get-all-properties block-id)]
                        (NodeDynamicBlock/create block-id
                                                 (java.util.ArrayList. props)
                                                 (BlockBehaviour$Properties/copy Blocks/STONE)))
                      has-be?
                      (ScriptedEntityBlock. block-id tile-id
                        (carrier-block-props))
                      :else
                      (Block. (BlockBehaviour$Properties/copy Blocks/STONE)))]
      (registry/register-block registry-name block-obj)
      (swap! registered-blocks assoc block-id block-obj))))

(defn register-scripted-tile-hooks!
  "Register metadata-driven scripted tile hooks from Tile DSL (or legacy block DSL)."
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

(defn register-block-entities []
  "Register BlockEntityTypes: one per tile-id."
  (doseq [tile-id (registry-metadata/get-all-tile-ids)]
    (let [registry-name (registry-metadata/get-tile-registry-name tile-id)
          block-ids (registry-metadata/get-tile-block-ids tile-id)
          pairs (keep (fn [block-id]
                        (when-let [inst (get @registered-blocks block-id)]
                          [block-id inst]))
                      block-ids)
          block-insts (mapv second pairs)]
      (when (seq block-insts)
        (let [block-id-by-inst (java.util.IdentityHashMap.)]
          (doseq [[block-id inst] pairs]
            (.put block-id-by-inst inst block-id))
          (let [type-holder (object-array 1)
                be-type (-> (BlockEntityType$Builder/of
                              (reify BlockEntityType$BlockEntitySupplier
                                (create [_ pos state]
                                  (let [block-inst (.getBlock state)
                                        block-id (or (.get block-id-by-inst block-inst) tile-id)]
                                    (ScriptedBlockEntity. (aget type-holder 0) pos state tile-id block-id))))
                              (into-array Block block-insts))
                            (.build nil))]
            (aset type-holder 0 be-type)
            (ScriptedBlockEntity/registerType tile-id be-type)
            (let [res-loc (ResourceLocation. modid/MOD-ID registry-name)]
              (Registry/register BuiltInRegistries/BLOCK_ENTITY_TYPE res-loc be-type)
              (swap! registered-block-entities assoc tile-id be-type)
              (log/info "Registered scripted BlockEntityType:" tile-id registry-name))))))))

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

  ;; Initialize core systems and load all content namespaces (blocks/items/guis/tiles).
  ;; This is required so metadata-driven registration sees the full registry.
  (core/init)
  
  ;; Initialize BlockState properties from Clojure metadata
  ;; Must happen before block registration so Property objects are ready
  (bsp/init-all-properties!)
  
  ;; Register blocks and items using metadata-driven approach
  ;; DSL systems are automatically initialized when namespaces load
  (register-scripted-tile-hooks!)
  (register-blocks)
  (register-block-entities)
  (register-items)

  ;; Initialize GUI system (common + server). Client-side screen registration is
  ;; performed in the Fabric client entrypoint.
  (gui-init/init-common!)
  (gui-init/init-server!)
  
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

(defn get-registered-block-entity-type
  "Get a registered BlockEntityType by tile-id or block-id."
  [tile-or-block-id]
  (let [tile-id (or (when (contains? @registered-block-entities tile-or-block-id)
                      tile-or-block-id)
                    (registry-metadata/get-block-tile-id tile-or-block-id))]
    (get @registered-block-entities tile-id)))

(defn get-registered-block-item
  "Get a registered block item by its block ID.
  
  Args:
    block-id: String - DSL block identifier (e.g., \"demo-block\")
  
  Returns:
    BlockItem - The registered block item, or nil if not found"
  [block-id]
  (get @registered-items (str block-id "-item")))
