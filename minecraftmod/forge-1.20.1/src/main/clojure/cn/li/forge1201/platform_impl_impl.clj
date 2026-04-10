(ns cn.li.forge1201.platform-impl-impl
  "Forge 1.20.1 platform implementation.

  Extends core platform protocols to Forge 1.20.1's Minecraft classes:
  - CompoundTag (was NBTTagCompound in 1.16.5)
  - ListTag (was NBTTagList in 1.16.5)
  - BlockPos (moved from util.math to core package in 1.18+)
  - Level (was World in 1.16.5)

  This module must be loaded during mod initialization to register
  platform implementations before any core code runs."
  (:require [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.item :as item]
            [cn.li.mcmod.platform.resource :as resource]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.be :as platform-be]
             [cn.li.mcmod.platform.events :as platform-events]
             [cn.li.mcmod.util.log :as log]
             [cn.li.forge1201.platform-impl :as platform-impl])
  (:import [net.minecraft.nbt CompoundTag ListTag]
           [net.minecraft.core BlockPos]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.item Item ItemStack]
           [net.minecraft.world.level Level]
           [net.minecraft.world.level.block.entity BlockEntity]
           [net.minecraft.world.level.block.state BlockState StateDefinition]
           [net.minecraft.world.level.block.state.properties Property]
           [net.minecraft.world.entity Entity]
           [net.minecraft.world.entity.player Player Inventory]
           [net.minecraft.world.inventory AbstractContainerMenu]
           [net.minecraftforge.common.util LazyOptional]
           [cn.li.forge1201.bridge ForgeRuntimeBridge]
           [cn.li.forge1201.block.entity ScriptedBlockEntity]
           [cn.li.forge1201.capability NamedCapabilityRegistry]
           ))

(set! *warn-on-reflection* true)

(defonce ^:private platform-extensions-installed? (atom false))

;; ============================================================================
;; NBT Protocol Implementation (Forge 1.20.1)
;; ============================================================================

(defn- install-nbt-impls!
  []
  (extend CompoundTag
    nbt/INBTCompound
    {:nbt-set-int! (fn [^CompoundTag this key value]
                     (.putInt this key (int value))
                     this)
     :nbt-get-int (fn [^CompoundTag this key]
                    (.getInt this key))
     :nbt-set-string! (fn [^CompoundTag this key value]
                        (.putString this key (str value))
                        this)
     :nbt-get-string (fn [^CompoundTag this key]
                       (.getString this key))
     :nbt-set-boolean! (fn [^CompoundTag this key value]
                         (.putBoolean this key (boolean value))
                         this)
     :nbt-get-boolean (fn [^CompoundTag this key]
                        (.getBoolean this key))
     :nbt-set-double! (fn [^CompoundTag this key value]
                        (.putDouble this key (double value))
                        this)
     :nbt-get-double (fn [^CompoundTag this key]
                       (.getDouble this key))
     :nbt-set-tag! (fn [^CompoundTag this key tag]
                     (.put this key tag)
                     this)
     :nbt-get-tag (fn [^CompoundTag this key]
                    (.get this key))
     :nbt-get-compound (fn [^CompoundTag this key]
                         (.getCompound this key))
     :nbt-get-list (fn [^CompoundTag this key]
                     (.getList this key 10))
     :nbt-has-key? (fn [^CompoundTag this key]
                     (.contains this key))
     :nbt-set-float! (fn [^CompoundTag this key value]
                       (.putFloat this key (float value))
                       this)
     :nbt-get-float (fn [^CompoundTag this key]
                      (.getFloat this key))
     :nbt-set-long! (fn [^CompoundTag this key value]
                      (.putLong this key (long value))
                      this)
     :nbt-get-long (fn [^CompoundTag this key]
                     (.getLong this key))})

  (extend ListTag
    nbt/INBTList
    {:nbt-append! (fn [^ListTag this element]
                    (.add this element)
                    this)
     :nbt-list-size (fn [^ListTag this]
                      (.size this))
     :nbt-list-get (fn [^ListTag this index]
                     (when (and (>= index 0) (< index (.size this)))
                       (.get this (int index))))
     :nbt-list-get-compound (fn [^ListTag this index]
                              (when (and (>= index 0) (< index (.size this)))
                                (.getCompound this (int index))))}))

;; ============================================================================
;; Position Protocol Implementation (Forge 1.20.1)
;; ============================================================================

(defn- install-position-impls!
  []
  (extend BlockPos
    pos/IBlockPos
    {:pos-x (fn [^BlockPos this]
              (.getX this))
     :pos-y (fn [^BlockPos this]
              (.getY this))
     :pos-z (fn [^BlockPos this]
              (.getZ this))}))

;; ============================================================================
;; ItemStack Protocol Implementation (Forge 1.20.1)
;; ============================================================================

(defn- install-itemstack-impls!
  []
  (extend ItemStack
    item/IItemStack
    {:item-is-empty? (fn [^ItemStack this]
                       (.isEmpty this))
     :item-get-count (fn [^ItemStack this]
                       (.getCount this))
     :item-get-max-stack-size (fn [^ItemStack this]
                                (.getMaxStackSize this))
     :item-is-equal? (fn [^ItemStack this other]
                       (ItemStack/isSameItem this ^ItemStack other))
     :item-save-to-nbt (fn [^ItemStack this nbt-tag]
                         (.save this nbt-tag))
     :item-get-or-create-tag (fn [^ItemStack this]
                               (.getOrCreateTag this))
     :item-get-max-damage (fn [^ItemStack this]
                            (.getMaxDamage this))
     :item-set-damage! (fn [^ItemStack this damage]
                         (.setDamageValue this (int damage)))
     :item-get-damage (fn [^ItemStack this]
                        (.getDamageValue this))
     :item-split (fn [^ItemStack this amount]
                   (.split this (int amount)))
     :item-get-item (fn [^ItemStack this]
                      (.getItem this))
     :item-get-tag-compound (fn [^ItemStack this]
                              (.getTag this))})

  (extend Item
    item/IItem
    {:item-get-description-id (fn [^Item this]
                                (.getDescriptionId this))
     :item-get-registry-name (fn [^Item this]
                               (try
                                 (ForgeRuntimeBridge/getItemRegistryPath this)
                                 (catch Exception _
                                   nil)))}))

(defn- install-block-and-world-impls!
  []
  (extend BlockEntity
    pos/IHasPosition
    {:position-get-block-pos (fn [^BlockEntity this]
                               (.getBlockPos this))
     :position-get-pos (fn [^BlockEntity this]
                         (.getBlockPos this))}

    platform-cap/ICapabilityProvider
    {:get-capability (fn [^BlockEntity this cap side]
                       (.getCapability this cap side))}

    platform-be/IBlockEntity
    {:be-get-level (fn [^BlockEntity this]
                     (.getLevel this))
     :be-get-world (fn [^BlockEntity this]
                     (.getLevel this))
     :be-get-custom-state (fn [^BlockEntity this]
                            (when (instance? ScriptedBlockEntity this)
                              (.getCustomState ^ScriptedBlockEntity this)))
     :be-set-custom-state! (fn [^BlockEntity this state]
                             (when (instance? ScriptedBlockEntity this)
                               (.setCustomState ^ScriptedBlockEntity this state)))
     :be-get-block-id (fn [^BlockEntity this]
                        (when (instance? ScriptedBlockEntity this)
                          (.getBlockId ^ScriptedBlockEntity this)))
     :be-set-changed! (fn [^BlockEntity this]
                        (.setChanged this))})

  (extend BlockState
    world/IBlockStateOps
    {:block-state-is-air (fn [^BlockState this]
                           (.isAir this))
     :block-state-get-block (fn [^BlockState this]
                              (.getBlock this))
     :block-state-get-state-definition (fn [^BlockState this]
                                         (.getStateDefinition (.getBlock this)))
     :block-state-get-property (fn [^BlockState _this state-def prop-name]
                                 (.getProperty ^StateDefinition state-def ^String prop-name))
     :block-state-set-property (fn [^BlockState this prop value]
                                 (.setValue this ^Property prop value))})

  (extend LazyOptional
    platform-cap/ILazyOptional
    {:is-present? (fn [^LazyOptional this]
                    (.isPresent this))
     :or-else (fn [^LazyOptional this default]
                (.orElse this default))})

  (extend Level
    world/IWorldAccess
    {:world-get-tile-entity (fn [^Level this block-pos]
                              (.getBlockEntity this block-pos))
     :world-get-block-state (fn [^Level this block-pos]
                              (.getBlockState this block-pos))
     :world-set-block (fn [^Level this block-pos state flags]
                        (.setBlock this block-pos state flags))
     :world-remove-block (fn [^Level this block-pos]
                           (.destroyBlock this block-pos false))
     :world-break-block (fn [^Level this block-pos drop?]
                          (.destroyBlock this block-pos (boolean drop?)))
     :world-place-block-by-id (fn [^Level this block-id block-pos flags]
                                (if-let [block (ForgeRuntimeBridge/getBlockById (str block-id))]
                                  (.setBlock this block-pos (.defaultBlockState block) flags)
                                  false))
     :world-is-chunk-loaded? (fn [^Level this chunk-x chunk-z]
                               (.hasChunk this chunk-x chunk-z))
     :world-get-day-time (fn [^Level this]
                           (.getDayTime this))
     :world-is-raining (fn [^Level this]
                         (.isRaining this))
     :world-is-client-side (fn [^Level this]
                             (.isClientSide this))
     :world-can-see-sky (fn [^Level this block-pos]
                          (.canSeeSky this block-pos))}))

;; ==========================================================================
;; Entity / Player / Inventory / Menu protocol implementations
;; ==========================================================================

;; Defer entity-related `extend-type` expansions until runtime to avoid
;; triggering Minecraft registry access during AOT/checkClojure. These
;; implementations are installed during `init-platform!` below.
(defn- install-entity-impls!
  []
  (extend Entity
    entity/IEntityOps
    {:entity-distance-to-sqr (fn [^Entity this x y z]
                               (.distanceToSqr this (double x) (double y) (double z)))})

  (extend Player
    entity/IEntityOps
    {:entity-distance-to-sqr (fn [^Player this x y z]
                               (.distanceToSqr this (double x) (double y) (double z)))
     :player-get-level (fn [^Player this]
                         (.level this))
     :player-get-name (fn [^Player this]
                        (str (.getName this)))
     :player-get-uuid (fn [^Player this]
                        (.getUUID this))
     :player-get-container-menu (fn [^Player this]
                                  (.containerMenu this))}))

(defn- install-inventory-and-menu-impls!
  []
  (extend Inventory
    entity/IEntityOps
    {:inventory-get-player (fn [^Inventory this]
                             (.player this))})

  (extend AbstractContainerMenu
    entity/IEntityOps
    {:menu-get-container-id (fn [^AbstractContainerMenu this]
                              (.containerId this))}))

;; ============================================================================
;; Platform Initialization
;; ============================================================================

(defn init-platform!
  "Initialize Forge 1.20.1 platform implementations.
  
  Registers factory functions for NBT and position creation.
  Must be called during mod initialization before any core code runs."
  []
  (log/info "Initializing Forge 1.20.1 platform implementations...")

  ;; Install protocol extensions only during real mod initialization.
  (when (compare-and-set! platform-extensions-installed? false true)
    (install-nbt-impls!)
    (install-position-impls!)
    (install-itemstack-impls!)
    (install-block-and-world-impls!)
    (install-entity-impls!)
    (install-inventory-and-menu-impls!))
  
  ;; Register NBT factory
  (alter-var-root #'nbt/*nbt-factory*
    (constantly {:create-compound #(CompoundTag.)
                 :create-list #(ListTag.)}))
  
  ;; Register position factory
  (alter-var-root #'pos/*position-factory*
    (constantly (fn [x y z]
                  (BlockPos. (int x) (int y) (int z)))))
  
  ;; Register ItemStack factory
  (alter-var-root #'item/*item-factory*
    (constantly
      (fn [nbt]
        (ItemStack/of ^CompoundTag nbt))))

  ;; Register resource identifier factory
  (alter-var-root #'resource/*resource-factory*
    (constantly (fn [namespace path]
                  (ResourceLocation. namespace path))))

  ;; Register named capability slots by key string.
  (letfn [(reg-cap! [key-kw]
            (NamedCapabilityRegistry/getOrCreate (name key-kw)))]

    ;; Bind the Forge implementation of declare-capability!
    ;; When ac calls (declare-capability! :wireless-node ...) this assigns its slot.
    (alter-var-root #'platform-cap/*declare-capability-impl*
                    (constantly (fn [key _java-type] (reg-cap! key))))

    ;; Bind the platform BE capability slot lookup used by platform-be/get-capability.
    (alter-var-root #'platform-be/*be-capability-slot-fn*
                    (constantly #(NamedCapabilityRegistry/get %)))

    ;; Bind event firing to the Forge game event bus.
    (alter-var-root #'platform-events/*fire-event-fn*
                    (constantly (fn [event] (ForgeRuntimeBridge/postEvent event))))

    ;; Client-side rendering bindings are now handled in client initialization.
    ;; See forge-1.20.1/client/init.clj for pose stack and render buffer setup.

    ;; Retroactively register capabilities already declared before this ran.
    (doseq [[key _] @platform-cap/capability-type-registry]
      (reg-cap! key)))
  
  (log/info "Forge 1.20.1 platform implementations initialized successfully"))

(platform-impl/register-init! init-platform!)
