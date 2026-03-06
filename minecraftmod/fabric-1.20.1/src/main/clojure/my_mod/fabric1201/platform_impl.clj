(ns my-mod.fabric1201.platform-impl
  "Fabric 1.20.1 platform implementation.
  
  Extends core platform protocols to Fabric 1.20.1's Minecraft classes.
  This project uses Loom official Mojang mappings on Fabric, so Minecraft class
  names/packages match Forge 1.20.1:
  - CompoundTag, ListTag
  - BlockPos
  - Level
  
  This module must be loaded during mod initialization to register
  platform implementations before any core code runs."
  (:require [my-mod.platform.nbt :as nbt]
            [my-mod.platform.position :as pos]
            [my-mod.platform.world :as world]
            [my-mod.platform.item :as item]
            [my-mod.platform.resource :as resource]
            [my-mod.util.log :as log])
  (:import [net.minecraft.nbt CompoundTag ListTag]
           [net.minecraft.core BlockPos]
           [net.minecraft.world.level Level]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.resources ResourceLocation]))

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
    (.sameItem this other))
  
  (item-save-to-nbt [this nbt]
    (.save this nbt))
  
  (item-get-or-create-tag [this]
    (.getOrCreateTag this))
  
  (item-get-max-damage [this]
    (.getMaxDamage this))
  
  (item-set-damage! [this damage]
    (.setDamageValue this (int damage))))

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
  
  (world-is-chunk-loaded? [this chunk-x chunk-z]
    (.hasChunk this chunk-x chunk-z)))

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

  ;; Register resource identifier factory
  (alter-var-root #'resource/*resource-factory*
    (constantly (fn [namespace path]
                  (ResourceLocation. namespace path))))
  
  (log/info "Fabric 1.20.1 platform implementations initialized successfully"))
