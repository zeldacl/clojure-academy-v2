(ns cn.li.mcmod.platform.ops-entity
  "Entity and player operation abstractions.
  
  This namespace consolidates protocols for entity/player queries and modifications:
  - Entity/Player info queries (IEntityOps)
  - Generic entity movement (IEntityMotion)
  - Player-specific physics (IPlayerMotion)
  - Damage application (IEntityDamage)
  - Damage event handlers (IDamageInterception)
  - Player teleportation (ITeleportation)
  - Status effects (IPotionEffects)
  - Runtime player/entity queries (IRuntimeInterop)"
  (:require
    [cn.li.mcmod.platform.entity]
    [cn.li.mcmod.platform.entity-motion]
    [cn.li.mcmod.platform.player-motion]
    [cn.li.mcmod.platform.entity-damage]
    [cn.li.mcmod.platform.damage-interception]
    [cn.li.mcmod.platform.teleportation]
    [cn.li.mcmod.platform.potion-effects]
    [cn.li.mcmod.platform.runtime-interop]))

;; =============================================================================
;; Re-exports for consolidated access
;; =============================================================================

;; Entity/Player info
(def IEntityOps cn.li.mcmod.platform.entity/IEntityOps)

;; Entity motion
(def IEntityMotion cn.li.mcmod.platform.entity-motion/IEntityMotion)

;; Player motion
(def IPlayerMotion cn.li.mcmod.platform.player-motion/IPlayerMotion)

;; Damage application
(def IEntityDamage cn.li.mcmod.platform.entity-damage/IEntityDamage)

;; Damage interception
(def IDamageInterception cn.li.mcmod.platform.damage-interception/IDamageInterception)

;; Teleportation
(def ITeleportation cn.li.mcmod.platform.teleportation/ITeleportation)

;; Potion effects
(def IPotionEffects cn.li.mcmod.platform.potion-effects/IPotionEffects)

;; Runtime interop
(def IRuntimeInterop cn.li.mcmod.platform.runtime-interop/IRuntimeInterop)
