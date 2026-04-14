(ns cn.li.mcmod.platform.entity
  "Platform-neutral entity utilities.

  This namespace defines a protocol for entity/player/menu operations that
  platform adapters (forge/fabric) must implement. Avoid importing any
  `net.minecraft.*` classes here so this namespace can be compiled without
  Minecraft on the classpath.")

(defprotocol IEntityOps
  (entity-distance-to-sqr [entity x y z]
    "Calculate squared distance from entity to coordinates: returns double")

  (player-get-level [player]
    "Return the Level/World for a player")

  (player-creative? [player]
    "Return true when the player is in a creative-like mode")

  (player-spectator? [player]
    "Return true when the player is a spectator")

  (player-get-name [player]
    "Return the player's name as a string")

  (player-get-uuid [player]
    "Return the player's UUID")

  (player-get-container-menu [player]
    "Return the player's open container/menu")

  (inventory-get-player [inventory]
    "Return the Player owning the Inventory")

  (menu-get-container-id [menu]
    "Return the containerId (window id) from an AbstractContainerMenu"))
