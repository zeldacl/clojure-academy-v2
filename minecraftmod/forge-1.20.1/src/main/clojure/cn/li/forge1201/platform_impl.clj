(ns cn.li.forge1201.platform-impl
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
             [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.nbt CompoundTag ListTag]
           [net.minecraft.core BlockPos]
           [net.minecraft.resources ResourceLocation]
          [org.joml Matrix4f]
          [cn.li.acapi.wireless WirelessCapabilities WirelessCapabilityKeys]
          [cn.li.forge1201.capability NamedCapabilityRegistry]
          [net.minecraftforge.common MinecraftForge]))

(set! *warn-on-reflection* true)

(defn- eval-with-source
  [form]
  (eval (with-meta form {:file "cn/li/forge1201/platform_impl.clj" :line 1})))

;; ============================================================================
;; NBT Protocol Implementation (Forge 1.20.1)
;; ============================================================================

(extend-type CompoundTag
  nbt/INBTCompound

  (nbt-set-int! [^CompoundTag this key value]
    (.putInt this key (int value))
    this)

  (nbt-get-int [^CompoundTag this key]
    (.getInt this key))

  (nbt-set-string! [^CompoundTag this key value]
    (.putString this key (str value))
    this)

  (nbt-get-string [^CompoundTag this key]
    (.getString this key))

  (nbt-set-boolean! [^CompoundTag this key value]
    (.putBoolean this key (boolean value))
    this)

  (nbt-get-boolean [^CompoundTag this key]
    (.getBoolean this key))

  (nbt-set-double! [^CompoundTag this key value]
    (.putDouble this key (double value))
    this)

  (nbt-get-double [^CompoundTag this key]
    (.getDouble this key))

  (nbt-set-tag! [^CompoundTag this key tag]
    (.put this key tag)
    this)

  (nbt-get-tag [^CompoundTag this key]
    (.get this key))

    (nbt-get-compound [^CompoundTag this key]
      (.getCompound this key))

  (nbt-get-list [^CompoundTag this key]
    (.getList this key 10))

  (nbt-has-key? [^CompoundTag this key]
    (.contains this key))

  (nbt-set-float! [^CompoundTag this key value]
    (.putFloat this key (float value))
    this)

  (nbt-get-float [^CompoundTag this key]
    (.getFloat this key))

  (nbt-set-long! [^CompoundTag this key value]
    (.putLong this key (long value))
    this)

  (nbt-get-long [^CompoundTag this key]
    (.getLong this key)))

(extend-type ListTag
  nbt/INBTList

  (nbt-append! [^ListTag this element]
    (.add this element)
    this)

  (nbt-list-size [^ListTag this]
    (.size this))

  (nbt-list-get [^ListTag this index]
    (when (and (>= index 0) (< index (.size this)))
      (.get this (int index))))

  (nbt-list-get-compound [^ListTag this index]
    (when (and (>= index 0) (< index (.size this)))
      (.getCompound this (int index)))))

;; ============================================================================
;; Position Protocol Implementation (Forge 1.20.1)
;; ============================================================================

(extend-type BlockPos
  pos/IBlockPos

  (pos-x [^BlockPos this]
    (.getX this))

  (pos-y [^BlockPos this]
    (.getY this))

  (pos-z [^BlockPos this]
    (.getZ this)))

;; ============================================================================
;; ItemStack Protocol Implementation (Forge 1.20.1)
;; ============================================================================

 (defn- install-itemstack-impls!
   []
   ;; Use runtime `eval` to defer `extend-type` macroexpansion until Minecraft
   ;; registries are bootstrapped (AOT/checkClojure would otherwise touch them).
   ;; Fully qualify protocol symbols: `eval` does not see this ns's `item` alias.
   (eval-with-source
     '(extend-type net.minecraft.world.item.ItemStack
        cn.li.mcmod.platform.item/IItemStack
        (item-is-empty? [^net.minecraft.world.item.ItemStack this] (.isEmpty this))
        (item-get-count [^net.minecraft.world.item.ItemStack this] (.getCount this))
        (item-get-max-stack-size [^net.minecraft.world.item.ItemStack this] (.getMaxStackSize this))
        ;; Mojang mappings (1.20.x): static helper `ItemStack.isSameItem(a, b)`
        ;; (instance method `isSameItem` may not exist depending on mappings).
        (item-is-equal? [^net.minecraft.world.item.ItemStack this other]
          (net.minecraft.world.item.ItemStack/isSameItem this ^net.minecraft.world.item.ItemStack other))
        (item-save-to-nbt [^net.minecraft.world.item.ItemStack this nbt] (.save this nbt))
        (item-get-or-create-tag [^net.minecraft.world.item.ItemStack this] (.getOrCreateTag this))
        (item-get-max-damage [^net.minecraft.world.item.ItemStack this] (.getMaxDamage this))
        (item-set-damage! [^net.minecraft.world.item.ItemStack this damage] (.setDamageValue this (int damage)))
        (item-get-damage [^net.minecraft.world.item.ItemStack this] (.getDamageValue this))
        (item-split [^net.minecraft.world.item.ItemStack this amount] (.split this (int amount)))
        (item-get-item [^net.minecraft.world.item.ItemStack this] (.getItem this))
        (item-get-tag-compound [^net.minecraft.world.item.ItemStack this] (.getTag this))))

   (eval-with-source
     '(extend-type net.minecraft.world.item.Item
        cn.li.mcmod.platform.item/IItem
        (item-get-description-id [^net.minecraft.world.item.Item this] (.getDescriptionId this))
        (item-get-registry-name [^net.minecraft.world.item.Item this]
          (try
            (let [regs-cls (Class/forName "net.minecraft.core.registries.BuiltInRegistries")
                  item-field (.getField regs-cls "ITEM")
                  item-registry (.get item-field nil)
                  get-key-method (.getMethod (class item-registry) "getKey" (into-array Class [Object]))
                  key (.invoke get-key-method item-registry (object-array [this]))]
              (when key
                (.getPath key)))
            (catch Exception _
              nil))))))

(defn- install-block-and-world-impls!
  []
  (eval-with-source
    '(extend-type net.minecraft.world.level.block.entity.BlockEntity
       cn.li.mcmod.platform.position/IHasPosition
       (position-get-block-pos [^net.minecraft.world.level.block.entity.BlockEntity this]
         (.getBlockPos this))
       (position-get-pos [^net.minecraft.world.level.block.entity.BlockEntity this]
         (.getBlockPos this))

       cn.li.mcmod.platform.capability/ICapabilityProvider
       (get-capability [^net.minecraft.world.level.block.entity.BlockEntity this cap side]
         (.getCapability this cap side))

       cn.li.mcmod.platform.be/IBlockEntity
       (be-get-level [^net.minecraft.world.level.block.entity.BlockEntity this]
         (.getLevel this))
       (be-get-world [^net.minecraft.world.level.block.entity.BlockEntity this]
         (.getLevel this))
       (be-get-custom-state [^net.minecraft.world.level.block.entity.BlockEntity this]
         (when (instance? cn.li.forge1201.block.entity.ScriptedBlockEntity this)
           (.getCustomState ^cn.li.forge1201.block.entity.ScriptedBlockEntity this)))
       (be-set-custom-state! [^net.minecraft.world.level.block.entity.BlockEntity this state]
         (when (instance? cn.li.forge1201.block.entity.ScriptedBlockEntity this)
           (.setCustomState ^cn.li.forge1201.block.entity.ScriptedBlockEntity this state)))
       (be-get-block-id [^net.minecraft.world.level.block.entity.BlockEntity this]
         (when (instance? cn.li.forge1201.block.entity.ScriptedBlockEntity this)
           (.getBlockId ^cn.li.forge1201.block.entity.ScriptedBlockEntity this)))
       (be-set-changed! [^net.minecraft.world.level.block.entity.BlockEntity this]
         (.setChanged this))))

  (eval-with-source
    '(extend-type net.minecraft.world.level.block.state.BlockState
       cn.li.mcmod.platform.world/IBlockStateOps
       (block-state-is-air [^net.minecraft.world.level.block.state.BlockState this]
         (.isAir this))
       (block-state-get-block [^net.minecraft.world.level.block.state.BlockState this]
         (.getBlock this))
       (block-state-get-state-definition [^net.minecraft.world.level.block.state.BlockState this]
         (.getStateDefinition (.getBlock this)))
       (block-state-get-property [^net.minecraft.world.level.block.state.BlockState this state-def prop-name]
         (.getProperty ^net.minecraft.world.level.block.state.StateDefinition state-def ^String prop-name))
       (block-state-set-property [^net.minecraft.world.level.block.state.BlockState this prop value]
         (.setValue this ^net.minecraft.world.level.block.state.properties.Property prop value))))

  (eval-with-source
    '(extend-type net.minecraftforge.common.util.LazyOptional
       cn.li.mcmod.platform.capability/ILazyOptional
       (is-present? [^net.minecraftforge.common.util.LazyOptional this]
         (.isPresent this))
       (or-else [^net.minecraftforge.common.util.LazyOptional this default]
         (.orElse this default))))

  (eval-with-source
    '(extend-type net.minecraft.world.level.Level
       cn.li.mcmod.platform.world/IWorldAccess
       (world-get-tile-entity [^net.minecraft.world.level.Level this block-pos]
         (.getBlockEntity this block-pos))
       (world-get-block-state [^net.minecraft.world.level.Level this block-pos]
         (.getBlockState this block-pos))
       (world-set-block [^net.minecraft.world.level.Level this block-pos state flags]
         (.setBlock this block-pos state flags))
       (world-remove-block [^net.minecraft.world.level.Level this block-pos]
         (.destroyBlock this block-pos false))
       (world-break-block [^net.minecraft.world.level.Level this block-pos drop?]
         (.destroyBlock this block-pos (boolean drop?)))
       (world-place-block-by-id [^net.minecraft.world.level.Level this block-id block-pos flags]
         (if-let [get-registered-block (requiring-resolve 'cn.li.forge1201.mod/get-registered-block)]
           (if-let [block (get-registered-block block-id)]
             (.setBlock this block-pos (.defaultBlockState block) flags)
             false)
           false))
       (world-is-chunk-loaded? [^net.minecraft.world.level.Level this chunk-x chunk-z]
         (.hasChunk this chunk-x chunk-z))
       (world-get-day-time [^net.minecraft.world.level.Level this]
         (.getDayTime this))
       (world-is-raining [^net.minecraft.world.level.Level this]
         (.isRaining this))
       (world-is-client-side [^net.minecraft.world.level.Level this]
         (.isClientSide this))
       (world-can-see-sky [^net.minecraft.world.level.Level this pos]
         (.canSeeSky this pos)))))

;; ==========================================================================
;; Entity / Player / Inventory / Menu protocol implementations
;; ==========================================================================

;; Defer entity-related `extend-type` expansions until runtime to avoid
;; triggering Minecraft registry access during AOT/checkClojure. These
;; implementations are installed during `init-platform!` below.
(defn- install-entity-impls!
  []
  (eval-with-source
    '(extend-type net.minecraft.world.entity.Entity
       cn.li.mcmod.platform.entity/IEntityOps
       (entity-distance-to-sqr [^net.minecraft.world.entity.Entity this x y z]
         (.distanceToSqr this (double x) (double y) (double z)))))

  (eval-with-source
    '(extend-type net.minecraft.world.entity.player.Player
       cn.li.mcmod.platform.entity/IEntityOps
       (entity-distance-to-sqr [^net.minecraft.world.entity.player.Player this x y z]
         (.distanceToSqr this (double x) (double y) (double z)))
       (player-get-level [^net.minecraft.world.entity.player.Player this]
         (.level this))
       (player-get-name [^net.minecraft.world.entity.player.Player this]
         (str (.getName this)))
       (player-get-uuid [^net.minecraft.world.entity.player.Player this]
         (.getUUID this))
       (player-get-container-menu [^net.minecraft.world.entity.player.Player this]
         (.containerMenu this)))))

(defn- install-inventory-and-menu-impls!
  []
  (eval-with-source
    '(extend-type net.minecraft.world.entity.player.Inventory
       cn.li.mcmod.platform.entity/IEntityOps
       (inventory-get-player [^net.minecraft.world.entity.player.Inventory this]
         (.player this))))

  (eval-with-source
    '(extend-type net.minecraft.world.inventory.AbstractContainerMenu
       cn.li.mcmod.platform.entity/IEntityOps
       (menu-get-container-id [^net.minecraft.world.inventory.AbstractContainerMenu this]
         (.containerId this)))))

;; ============================================================================
;; Platform Initialization
;; ============================================================================

(defn init-platform!
  "Initialize Forge 1.20.1 platform implementations.
  
  Registers factory functions for NBT and position creation.
  Must be called during mod initialization before any core code runs."
  []
  (log/info "Initializing Forge 1.20.1 platform implementations...")

  ;; Install protocol extensions that may indirectly trigger Minecraft class loading.
  ;; This must run only during real mod initialization, not during AOT/checkClojure.
  (install-itemstack-impls!)
  (install-block-and-world-impls!)
  ;; Install entity/inventory/menu impls at runtime to avoid registry bootstrap issues
  (install-entity-impls!)
  (install-inventory-and-menu-impls!)
  
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
        (net.minecraft.world.item.ItemStack/of ^CompoundTag nbt))))

  ;; Register resource identifier factory
  (alter-var-root #'resource/*resource-factory*
    (constantly (fn [namespace path]
                  (ResourceLocation. namespace path))))

  ;; Helper: map a keyword cap key to the correct named or dynamic Capability object.
  ;; The four wireless capabilities use the public WirelessCapabilities constants so
  ;; external mods can discover them by importing WirelessCapabilities.  All other
  ;; capability keys (e.g. :wireless-energy) get a single anonymous token.
  (letfn [(reg-cap! [key-kw]
            (let [key-str (name key-kw)
                  named   (get {WirelessCapabilityKeys/MATRIX    WirelessCapabilities/MATRIX
                                WirelessCapabilityKeys/NODE      WirelessCapabilities/NODE
                                WirelessCapabilityKeys/GENERATOR WirelessCapabilities/GENERATOR
                                WirelessCapabilityKeys/RECEIVER  WirelessCapabilities/RECEIVER}
                               key-str)]
              (if named
                (NamedCapabilityRegistry/register key-str named)
                (NamedCapabilityRegistry/getOrCreate key-str))))]

    ;; Bind the Forge implementation of declare-capability!
    ;; When ac calls (declare-capability! :wireless-node ...) this assigns its slot.
    (alter-var-root #'platform-cap/*declare-capability-impl*
                    (constantly (fn [key _java-type] (reg-cap! key))))

    ;; Bind the platform BE capability slot lookup used by platform-be/get-capability.
    (alter-var-root #'platform-be/*be-capability-slot-fn*
                    (constantly #(NamedCapabilityRegistry/get %)))

    ;; Bind event firing to the Forge game event bus.
    (alter-var-root #'platform-events/*fire-event-fn*
                    (constantly (fn [event] (.post MinecraftForge/EVENT_BUS event))))

    ;; Client-side rendering bindings are now handled in client initialization.
    ;; See forge-1.20.1/client/init.clj for pose stack and render buffer setup.

    ;; Retroactively register capabilities already declared before this ran.
    (doseq [[key _] @platform-cap/capability-type-registry]
      (reg-cap! key)))
  
  (log/info "Forge 1.20.1 platform implementations initialized successfully"))
