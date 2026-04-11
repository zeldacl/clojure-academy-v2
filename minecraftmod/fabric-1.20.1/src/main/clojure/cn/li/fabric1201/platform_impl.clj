(ns cn.li.fabric1201.platform-impl
  "Fabric 1.20.1 platform implementation.
  
  Extends core platform protocols to Fabric 1.20.1's Minecraft classes.
  This project uses Loom official Mojang mappings on Fabric, so Minecraft class
  names/packages match Forge 1.20.1:
  - CompoundTag, ListTag
  - BlockPos
  - Level
  
  This module must be loaded during mod initialization to register
  platform implementations before any core code runs."
  (:require [cn.li.fabric1201.platform.nbt :as nbt]
            [cn.li.fabric1201.platform.position :as pos]
            [cn.li.fabric1201.platform.world :as world]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.fabric1201.platform.item :as item]
            [cn.li.fabric1201.platform.resource :as resource]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.fabric1201.client.render.buffer :as buffer]
            [cn.li.mcmod.platform.position :as mcmod-pos]
            [cn.li.mcmod.platform.be :as pbe])
  (:import [net.minecraft.nbt CompoundTag ListTag]
           [net.minecraft.core BlockPos]
           [net.minecraft.world.level Level]
           [net.minecraft.world.level.block.state BlockState]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.resources ResourceLocation]
           [cn.li.fabric1201.block.entity ScriptedBlockEntity]))

;; ============================================================================
;; NBT Protocol Implementation (Fabric 1.20.1)
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
    (.contains this key)))

(extend-type ListTag
  nbt/INBTList
  
  (nbt-append! [this element]
    (.add this element)
    this)
  
  (nbt-list-size [this]
    (.size this))
  
  (nbt-list-get [this index]
    (when (and (>= index 0) (< index (.size this)))
      (.get this index)))
  
  (nbt-list-get-compound [this index]
    (when (and (>= index 0) (< index (.size this)))
      (.getCompound this index))))

;; ============================================================================
;; ItemStack Protocol Implementation (Fabric 1.20.1)
;; ============================================================================

(extend-type ItemStack
  item/IItemStack
  
  (item-is-empty? [this]
    (.isEmpty this))
  
  (item-get-count [this]
    (.getCount this))
  
  (item-get-max-stack-size [this]
    (.getMaxStackSize this))
  
  (item-is-equal? [this other]
    (ItemStack/matches this other))
  
  (item-save-to-nbt [this nbt]
    (.save this nbt))
  
  (item-get-or-create-tag [this]
    (.getOrCreateTag this))
  
  (item-get-max-damage [this]
    (.getMaxDamage this))
  
  (item-set-damage! [this damage]
    (.setDamageValue this (int damage)))

  (item-get-damage [this]
    (.getDamageValue this))
  (item-get-item [this]
    (.getItem this))

  (item-get-tag-compound [this]
    (.getTag this))

  (item-split [this amount]
    (.split this (int amount))))

;; ============================================================================
;; Position Protocol Implementation (Fabric 1.20.1)
;; ============================================================================

(extend-type BlockPos
  pos/IBlockPos
  
  (pos-x [this]
    (.getX this))
  
  (pos-y [this]
    (.getY this))
  
  (pos-z [this]
    (.getZ this)))

;; ==========================================================================
;; BlockState protocol implementations for Fabric
;; ==========================================================================

(extend-type BlockState
  world/IBlockStateOps
  (block-state-is-air [this]
    (.isAir this)))

(extend-type BlockState
  world/IBlockStateOps
  (block-state-get-block [this]
    (.getBlock this))
  (block-state-get-state-definition [this]
    (.getStateDefinition (.getBlock this)))
  (block-state-get-property [this state-def prop-name]
    (.getProperty state-def prop-name))
  (block-state-set-property [this prop value]
    (.setValue this prop value)))

;; ============================================================================
;; World Protocol Implementation (Fabric 1.20.1)
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

  (world-break-block [this block-pos drop?]
    (.destroyBlock this block-pos (boolean drop?)))

  (world-place-block-by-id [this block-id block-pos flags]
    (if-let [get-registered-block (requiring-resolve 'cn.li.fabric1201.mod/get-registered-block)]
      (if-let [block (get-registered-block block-id)]
        (.setBlock this block-pos (.defaultBlockState block) flags)
        false)
      false))
  
  (world-is-chunk-loaded? [this chunk-x chunk-z]
    (.hasChunk this chunk-x chunk-z)))

;; ============================================================================
;; Scripted tile entity — mcmod IHasPosition / IBlockEntity (TESR / multiblock)
;; ============================================================================

(extend-type ScriptedBlockEntity
  mcmod-pos/IHasPosition
  (position-get-block-pos [this] (.getBlockPos ^ScriptedBlockEntity this))
  (position-get-pos [this] (.getBlockPos ^ScriptedBlockEntity this))
  pbe/IBlockEntity
  (be-get-level [this] (.getLevel ^ScriptedBlockEntity this))
  (be-get-world [this] (.getLevel ^ScriptedBlockEntity this))
  (be-get-custom-state [this] (.getCustomState ^ScriptedBlockEntity this))
  (be-set-custom-state! [this state] (.setCustomState ^ScriptedBlockEntity this state))
  (be-get-block-id [this] (.getBlockId ^ScriptedBlockEntity this))
  (be-set-changed! [this] (.setChanged ^ScriptedBlockEntity this)))

;; ============================================================================
;; Entity / Player / Inventory / Menu protocol implementations (Fabric)
;; ============================================================================

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
  "Initialize Fabric 1.20.1 platform implementations.
  
  Registers factory functions for NBT and position creation.
  Must be called during mod initialization before any core code runs."
  []
  (log/info "Initializing Fabric 1.20.1 platform implementations...")
  
  ;; Register NBT factory
  (alter-var-root #'nbt/*nbt-factory*
    (constantly {:create-compound #(CompoundTag.)
                 :create-list #(ListTag.)}))
  
  ;; Register position factory
  (alter-var-root #'pos/*position-factory*
    (constantly (fn [x y z]
                  (BlockPos. (int x) (int y) (int z)))))
  
  ;; Register item factory
  (alter-var-root #'item/*item-factory*
    (constantly (fn [nbt]
                  (ItemStack/of nbt))))

  ;; Bind platform PoseStack implementations for mcmod
  (alter-var-root #'pose/*y-rotation-fn*
    (constantly (fn [pose-stack angle]
                  (.mulPose pose-stack (.rotationDegrees com.mojang.math.Axis/YP (float angle))))))

  (alter-var-root #'pose/*push-pose-fn*
      (constantly (fn [pose-stack]
            (.pushPose pose-stack))))

  (alter-var-root #'pose/*pop-pose-fn*
    (constantly (fn [pose-stack]
                  (.popPose pose-stack))))

  (alter-var-root #'pose/*translate-fn*
    (constantly (fn [pose-stack x y z]
                  (.translate pose-stack (double x) (double y) (double z)))))

  ;; Bind platform accessor for current matrix from PoseStack
  (alter-var-root #'pose/*get-matrix-fn*
    (constantly (fn [pose-stack]
                  (let [entry (.last pose-stack)]
                    (.pose entry)))))

  ;; Bind submit-vertex helper to avoid core calling VertexConsumer methods
  (alter-var-root #'buffer/*submit-vertex-fn*
    (constantly (fn [vc matrix x y z r g b a u v overlay uv2 nx ny nz]
                  (-> (.vertex vc matrix (float x) (float y) (float z))
                      (.color (int r) (int g) (int b) (int a))
                      (.uv (float u) (float v))
                      (.overlayCoords (int overlay))
                      (.uv2 (int uv2))
                      (.normal (float nx) (float ny) (float nz))
                      (.endVertex))))))
