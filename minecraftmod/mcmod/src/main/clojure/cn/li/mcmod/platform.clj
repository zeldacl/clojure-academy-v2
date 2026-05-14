(ns cn.li.mcmod.platform
  "Platform abstraction layer - organized SPI operations index.
  
  ORGANIZATION
  ============
  Platform SPI operations are organized into logical groups:
  
  - ops-foundation: Position, NBT, Item, Resource, Capability abstractions
  - ops-world: World access, block manipulation, world effects
  - ops-entity: Entity/player operations, movement, damage, teleportation
  - ops-ability: Player ability/skill data storage
  - ops-integration: Event posting, command hooks, JEI/CraftTweaker, energy
  - ops-ui: Terminal UI rendering
  
  USAGE
  =====
  Prefer using specific ops-* namespaces for clarity:
  
    (:require [cn.li.mcmod.platform.ops-foundation :as ops])
    (ops/declare-capability! :my-cap MyClass factory-fn)
  
  Or use individual protocol namespaces when preferred:
  
    (:require [cn.li.mcmod.platform.capability])"
  (:require
    [cn.li.mcmod.platform.ops-foundation]
    [cn.li.mcmod.platform.ops-world]
    [cn.li.mcmod.platform.ops-entity]
    [cn.li.mcmod.platform.ops-ability]
    [cn.li.mcmod.platform.ops-integration]
    [cn.li.mcmod.platform.ops-ui]))
