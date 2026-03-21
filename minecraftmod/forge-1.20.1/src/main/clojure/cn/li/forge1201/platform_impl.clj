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
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.client.render.buffer :as buffer])
  (:import [net.minecraft.nbt CompoundTag ListTag]
           [net.minecraft.core BlockPos]
           [net.minecraft.world.level Level]
           [net.minecraft.world.level.block.state BlockState StateDefinition]
           [net.minecraft.world.level.block.state.properties Property]
           [net.minecraft.world.level.block.entity BlockEntity]
           [net.minecraft.resources ResourceLocation]
           [net.minecraftforge.common.util LazyOptional]
           [cn.li.forge1201.capability CapabilitySlots]))

;; ============================================================================
;; NBT Protocol Implementation (Forge 1.20.1)
;; ============================================================================

(extend-type CompoundTag
  nbt/INBTCompound
  
  (nbt-set-int! [this key value]
    (.putInt this key (int value))
    this)
  
  (nbt-get-int [this key]
    (.getInt this key))
  
  (nbt-set-string! [this key value]
    (.putString this key (str value))
    this)
  
  (nbt-get-string [this key]
    (.getString this key))
  
  (nbt-set-boolean! [this key value]
    (.putBoolean this key (boolean value))
    this)
  
  (nbt-get-boolean [this key]
    (.getBoolean this key))
  
  (nbt-set-double! [this key value]
    (.putDouble this key (double value))
    this)
  
  (nbt-get-double [this key]
    (.getDouble this key))
  
  (nbt-set-tag! [this key tag]
    (.put this key tag)
    this)
  
  (nbt-get-tag [this key]
    (.get this key))
  
    (nbt-get-compound [this key]
      (.getCompound this key))
  
  (nbt-get-list [this key]
    (.getList this key 10))
  
  (nbt-has-key? [this key]
    (.contains this key))

  (nbt-set-float! [this key value]
    (.putFloat this key (float value))
    this)

  (nbt-get-float [this key]
    (.getFloat this key))

  (nbt-set-long! [this key value]
    (.putLong this key (long value))
    this)

  (nbt-get-long [this key]
    (.getLong this key)))

(extend-type ListTag
  nbt/INBTList
  
  (nbt-append! [this element]
    (.add this element)
    this)
  
  (nbt-list-size [this]
    (.size this))
  
  (nbt-list-get [this index]
    (when (and (>= index 0) (< index (.size this)))
      (.get this (int index))))
  
  (nbt-list-get-compound [this index]
    (when (and (>= index 0) (< index (.size this)))
      (.getCompound this (int index)))))

;; ============================================================================
;; Position Protocol Implementation (Forge 1.20.1)
;; ============================================================================

(extend-type BlockPos
  pos/IBlockPos

  (pos-x [this]
    (.getX this))

  (pos-y [this]
    (.getY this))

  (pos-z [this]
    (.getZ this)))

;; Extend BlockEntity to support position access
(extend-type BlockEntity
  pos/IHasPosition

  (position-get-block-pos [this]
    (.getBlockPos this))

  (position-get-pos [this]
    ;; Fallback for older code that might call this
    (.getBlockPos this))

  platform-cap/ICapabilityProvider

  (get-capability [this cap side]
    (.getCapability this cap side))

  platform-be/IBlockEntity

  (be-get-level [this]
    (.getLevel this))

  (be-get-world [this]
    ;; Fallback for older code
    (.getLevel this))

  ;; Additional BE interop implemented in platform_impl (keeps core free of
  ;; direct Minecraft class references)
  (be-get-custom-state [this]
    (.getCustomState this))

  (be-set-custom-state! [this state]
    (.setCustomState this state))

  (be-get-block-id [this]
    (.getBlockId this))

  (be-set-changed! [this]
    (.setChanged this)))

;; ==========================================================================
;; BlockState protocol implementations for Forge
;; ==========================================================================

(extend-type BlockState
  world/IBlockStateOps
  (block-state-is-air [this]
    (.isAir this))
  (block-state-get-block [this]
    (.getBlock this))
  (block-state-get-state-definition [this]
    (.getStateDefinition (.getBlock this)))
  (block-state-get-property [this state-def prop-name]
    (.getProperty ^StateDefinition state-def ^String prop-name))
  (block-state-set-property [this prop value]
    (.setValue this ^Property prop value)))

;; ============================================================================
;; Capability LazyOptional Protocol Implementation (Forge 1.20.1)
;; ============================================================================

(extend-type LazyOptional
  platform-cap/ILazyOptional

  (is-present? [this]
    (.isPresent this))

  (or-else [this default]
    (.orElse this default)))

;; ============================================================================
;; World Protocol Implementation (Forge 1.20.1)

;; ============================================================================
;; ItemStack Protocol Implementation (Forge 1.20.1)
;; ============================================================================

(defn- install-itemstack-impls!
  []
  ;; Use runtime `eval` to defer `extend-type` macroexpansion until Minecraft
  ;; registries are bootstrapped (AOT/checkClojure would otherwise touch them).
  ;; Fully qualify protocol symbols: `eval` does not see this ns's `item` alias.
  (eval
    '(extend-type net.minecraft.world.item.ItemStack
       cn.li.mcmod.platform.item/IItemStack
       (item-is-empty? [this] (.isEmpty this))
       (item-get-count [this] (.getCount this))
       (item-get-max-stack-size [this] (.getMaxStackSize this))
     ;; Mojang mappings (1.20.x): static helper `ItemStack.isSameItem(a, b)`
     ;; (instance method `isSameItem` may not exist depending on mappings).
     (item-is-equal? [this other] (net.minecraft.world.item.ItemStack/isSameItem this ^net.minecraft.world.item.ItemStack other))
       (item-save-to-nbt [this nbt] (.save this nbt))
       (item-get-or-create-tag [this] (.getOrCreateTag this))
       (item-get-max-damage [this] (.getMaxDamage this))
       (item-set-damage! [this damage] (.setDamageValue this (int damage)))
       (item-get-damage [this] (.getDamageValue this))
      (item-split [this amount] (.split this (int amount)))
       (item-get-item [this] (.getItem this))
       (item-get-tag-compound [this] (.getTag this))))
  (eval
    '(extend-type net.minecraft.world.item.Item
       cn.li.mcmod.platform.item/IItem
       (item-get-description-id [this] (.getDescriptionId this)))))

;; ============================================================================
;; World Protocol Implementation (Forge 1.20.1)
;; ============================================================================
;; ============================================================================

(extend-type Level
  world/IWorldAccess
  
  (world-get-tile-entity [this block-pos]
    (.getBlockEntity this block-pos))
  
  (world-get-block-state [this block-pos]
    (.getBlockState this block-pos))
  
  (world-set-block [this block-pos state flags]
    (.setBlock this block-pos state flags))

  (world-remove-block [this block-pos]
    (.destroyBlock this block-pos false))

  (world-place-block-by-id [this block-id block-pos flags]
    (if-let [get-registered-block (requiring-resolve 'cn.li.forge1201.mod/get-registered-block)]
      (if-let [block (get-registered-block block-id)]
        (.setBlock this block-pos (.defaultBlockState block) flags)
        false)
      false))
  
  (world-is-chunk-loaded? [this chunk-x chunk-z]
    (.hasChunk this chunk-x chunk-z))

  (world-get-day-time [this]
    (.getDayTime this))

  (world-is-raining [this]
    (.isRaining this))

  (world-is-client-side [this]
    (.isClientSide this))

  (world-can-see-sky [this pos]
    (.canSeeSky this pos)))

;; ==========================================================================
;; Entity / Player / Inventory / Menu protocol implementations
;; ==========================================================================

(extend-type net.minecraft.world.entity.Entity
  cn.li.mcmod.platform.entity/IEntityOps
  (entity-distance-to-sqr [this x y z]
    (.distanceToSqr ^net.minecraft.world.entity.Entity this (double x) (double y) (double z))))

(extend-type net.minecraft.world.entity.player.Player
  cn.li.mcmod.platform.entity/IEntityOps
  (player-get-level [this]
    (.level ^net.minecraft.world.entity.player.Player this))
  (player-get-name [this]
    (str (.getName ^net.minecraft.world.entity.player.Player this)))
  (player-get-uuid [this]
    (.getUUID ^net.minecraft.world.entity.player.Player this))
  (player-get-container-menu [this]
    (.containerMenu ^net.minecraft.world.entity.player.Player this)))

(extend-type net.minecraft.world.entity.player.Inventory
  cn.li.mcmod.platform.entity/IEntityOps
  (inventory-get-player [this]
    (.player ^net.minecraft.world.entity.player.Inventory this)))

(extend-type net.minecraft.world.inventory.AbstractContainerMenu
  cn.li.mcmod.platform.entity/IEntityOps
  (menu-get-container-id [this]
    (.containerId ^net.minecraft.world.inventory.AbstractContainerMenu this)))

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
        (let [cls (Class/forName "net.minecraft.world.item.ItemStack")]
          (clojure.lang.Reflector/invokeStaticMethod cls "of" (object-array [nbt]))))))

  ;; Register resource identifier factory
  (alter-var-root #'resource/*resource-factory*
    (constantly (fn [namespace path]
                  (ResourceLocation. namespace path))))

  ;; Bind the Forge implementation of declare-capability!
  ;; When ac calls declare-capability!, this fn assigns a CapabilitySlots slot.
  (alter-var-root #'platform-cap/*declare-capability-impl*
                  (constantly (fn [key _java-type]
                  (CapabilitySlots/assign (name key)))))

  ;; Bind the platform BE capability slot lookup
  (alter-var-root #'platform-be/*be-capability-slot-fn*
                  (constantly (fn [key-string] (CapabilitySlots/get key-string))))

  ;; Bind platform PoseStack Y-rotation implementation for mcmod
  (alter-var-root #'pose/*y-rotation-fn*
    (constantly (fn [pose-stack angle]
                  (.mulPose pose-stack (.rotationDegrees com.mojang.math.Axis/YP (float angle))))))

  ;; Bind platform pose stack push/pop/translate implementations
  (alter-var-root #'pose/*push-pose-fn*
    (constantly (fn [pose-stack]
                  (.pushPose pose-stack))))

  (alter-var-root #'pose/*pop-pose-fn*
    (constantly (fn [pose-stack]
                  (.popPose pose-stack))))

  (alter-var-root #'pose/*translate-fn*
    (constantly (fn [pose-stack x y z]
                  (.translate pose-stack (double x) (double y) (double z)))))

  ;; Bind platform render buffer selectors for mcmod/ac renderers
  (alter-var-root #'buffer/*solid-buffer-fn*
    (constantly (fn [buffer-source texture]
                  (.getBuffer buffer-source (net.minecraft.client.renderer.RenderType/entitySolid texture)))))
  (alter-var-root #'buffer/*translucent-buffer-fn*
    (constantly (fn [buffer-source texture]
                  (.getBuffer buffer-source (net.minecraft.client.renderer.RenderType/entityTranslucent texture)))))
  (alter-var-root #'buffer/*cutout-no-cull-buffer-fn*
    (constantly (fn [buffer-source texture]
                  (.getBuffer buffer-source (net.minecraft.client.renderer.RenderType/entityCutoutNoCull texture)))))

  ;; Retroactively assign slots for capabilities already declared before this ran
  (doseq [[key {:keys [_java-type]}] @platform-cap/capability-type-registry]
    (CapabilitySlots/assign (name key)))
  
  (log/info "Forge 1.20.1 platform implementations initialized successfully"))
