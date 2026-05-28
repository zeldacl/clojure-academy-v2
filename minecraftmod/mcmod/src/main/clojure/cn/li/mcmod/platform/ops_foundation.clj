(ns cn.li.mcmod.platform.ops-foundation
  "Platform-agnostic data structure and factory abstractions.
  
  This namespace consolidates foundational protocols used across the platform layer:
  - Position/coordinate abstractions (IBlockPos, IHasPosition)
  - NBT serialization contracts (INBTCompound, INBTList)
  - Item/ItemStack abstractions (IItemStack, IItem)
  - Resource identifier factories (ResourceLocation)
  - Capability registration & slot management
  
  Platform implementations (forge, fabric) extend these protocols to Minecraft types
  and bind the factory functions at initialization."
  (:require
    ;; Re-export foundational protocols
    [cn.li.mcmod.platform.position]
    [cn.li.mcmod.platform.nbt]
    [cn.li.mcmod.platform.item]
    [cn.li.mcmod.platform.resource]
    [cn.li.mcmod.platform.capability]))

;; =============================================================================
;; Re-exports for backwards compatibility & consolidated access
;; =============================================================================

;; Position abstractions
(def IBlockPos cn.li.mcmod.platform.position/IBlockPos)
(def IHasPosition cn.li.mcmod.platform.position/IHasPosition)

;; NBT serialization
(def INBTCompound cn.li.mcmod.platform.nbt/INBTCompound)
(def INBTList cn.li.mcmod.platform.nbt/INBTList)

;; Item abstractions
(def IItemStack cn.li.mcmod.platform.item/IItemStack)
(def IItem cn.li.mcmod.platform.item/IItem)

;; Resource identifiers
(def create-resource-location cn.li.mcmod.platform.resource/create-resource-location)

;; Capability registration
(def capability-type-registry cn.li.mcmod.platform.capability/capability-type-registry-snapshot)
(def declare-capability! cn.li.mcmod.platform.capability/declare-capability!)
(def ICapabilityProvider cn.li.mcmod.platform.capability/ICapabilityProvider)
(def ILazyOptional cn.li.mcmod.platform.capability/ILazyOptional)

;; =============================================================================
;; Organization summary
;; =============================================================================

;; This namespace provides organized access to foundational platform abstractions.
;; Current organization:
;; - position.clj: IBlockPos (block coordinates), IHasPosition (any locatable entity)
;; - nbt.clj: INBTCompound, INBTList (NBT serialization)
;; - item.clj: IItemStack, IItem (Minecraft item representation)
;; - resource.clj: ResourceLocation factory (namespace:path identifiers)
;; - capability.clj: Capability registration & provider contracts
