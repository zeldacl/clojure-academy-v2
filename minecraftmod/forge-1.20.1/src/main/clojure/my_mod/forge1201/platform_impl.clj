(ns my-mod.forge1201.platform-impl
  "Forge 1.20.1 platform implementation.
  
  Extends core platform protocols to Forge 1.20.1's Minecraft classes:
  - CompoundTag (was NBTTagCompound in 1.16.5)
  - ListTag (was NBTTagList in 1.16.5)
  - BlockPos (moved from util.math to core package in 1.18+)
  - Level (was World in 1.16.5)
  
  This module must be loaded during mod initialization to register
  platform implementations before any core code runs."
  (:require [my-mod.platform.nbt :as nbt]
            [my-mod.platform.position :as pos]
            [my-mod.platform.world :as world]
                        [my-mod.platform.item :as item]
            [my-mod.util.log :as log])
  (:import [net.minecraft.nbt CompoundTag ListTag]
           [net.minecraft.core BlockPos]
           [net.minecraft.world.level Level]
           [net.minecraft.world.item ItemStack]))

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
      (.get this index))))

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

;; ============================================================================
;; World Protocol Implementation (Forge 1.20.1)

;; ============================================================================
;; ItemStack Protocol Implementation (Forge 1.20.1)
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
    (.sameItem this other))
  
  (item-save-to-nbt [this nbt]
    (.save this nbt)))

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
  
  (world-is-chunk-loaded? [this chunk-x chunk-z]
    (.hasChunk this chunk-x chunk-z)))

;; ============================================================================
;; Platform Initialization
;; ============================================================================

(defn init-platform!
  "Initialize Forge 1.20.1 platform implementations.
  
  Registers factory functions for NBT and position creation.
  Must be called during mod initialization before any core code runs."
  []
  (log/info "Initializing Forge 1.20.1 platform implementations...")
  
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
      (constantly (fn [nbt] (ItemStack/of nbt))))
  
  (log/info "Forge 1.20.1 platform implementations initialized successfully"))
