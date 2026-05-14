(ns cn.li.mcmod.platform.ops-world
  "Block and world manipulation abstractions.
  
  This namespace consolidates protocols for world state and block operations:
  - World/Level access and queries (IWorldAccess)
  - Block breaking/setting operations (IBlockManipulation)
  - Block Entity (Tile Entity) utilities (IBlockEntity + helpers)
  - World-level effects (lightning, explosions, particle queries)
  - Line-of-sight/block finding (IRaycast)
  - Location storage for teleportation support (ISavedLocations)"
  (:require
    [cn.li.mcmod.platform.world :as world]
    [cn.li.mcmod.platform.block-manipulation :as block-manipulation]
    [cn.li.mcmod.platform.be :as be]
    [cn.li.mcmod.platform.world-effects :as world-effects]
    [cn.li.mcmod.platform.raycast :as raycast]
    [cn.li.mcmod.platform.saved-locations :as saved-locations]))

(def IWorldAccess world/IWorldAccess)
(def IBlockManipulation block-manipulation/IBlockManipulation)
(def IBlockEntity be/IBlockEntity)
(def IWorldEffects world-effects/IWorldEffects)
(def IRaycast raycast/IRaycast)
(def ISavedLocations saved-locations/ISavedLocations)
