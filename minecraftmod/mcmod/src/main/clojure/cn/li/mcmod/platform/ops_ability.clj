(ns cn.li.mcmod.platform.ops-ability
  "Player ability/skill data storage abstractions.
  
  This namespace consolidates protocols for persistent player ability data:
  - IPlayerAbilityData: Main ability state storage per player
  - IResourceData: Resource pools (energy, mana, etc.)
  - ICooldownData: Ability cooldown tracking
  - IPresetData: Preset/configuration storage"
  (:require
    [cn.li.mcmod.platform.ability]))

;; =============================================================================
;; Re-exports for consolidated access
;; =============================================================================

(def IPlayerAbilityData cn.li.mcmod.platform.ability/IPlayerAbilityData)
(def IResourceData cn.li.mcmod.platform.ability/IResourceData)
(def ICooldownData cn.li.mcmod.platform.ability/ICooldownData)
(def IPresetData cn.li.mcmod.platform.ability/IPresetData)
