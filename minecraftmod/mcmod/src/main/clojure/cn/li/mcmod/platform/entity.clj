(ns cn.li.mcmod.platform.entity
  "Platform-neutral entity utilities."
  (:import [net.minecraft.world.entity Entity]
           [net.minecraft.world.entity.player Player Inventory]
           [net.minecraft.world.inventory AbstractContainerMenu]))

(defn entity-distance-to-sqr
  "Calculate squared distance from entity to coordinates.

  Args:
  - entity: Entity instance
  - x, y, z: target coordinates (doubles)

  Returns: squared distance (double)"
  [entity x y z]
  (.distanceToSqr ^Entity entity (double x) (double y) (double z)))

(defn player-get-level
  "Get the Level/World from a Player entity.

  Args:
  - player: Player instance

  Returns: Level instance"
  [player]
  (.level ^Player player))

(defn player-get-name
  "Get the name of a Player entity as a string.

  Args:
  - player: Player instance

  Returns: String - player name"
  [player]
  (str (.getName ^Player player)))

(defn player-get-uuid
  "Get the UUID of a Player entity.

  Args:
  - player: Player instance

  Returns: UUID"
  [player]
  (.getUUID ^Player player))

(defn player-get-container-menu
  "Get the player's currently open container menu.

  Args:
  - player: Player instance

  Returns: AbstractContainerMenu"
  [player]
  (.containerMenu ^Player player))

(defn inventory-get-player
  "Get the Player from a player Inventory.

  Args:
  - inventory: Inventory instance

  Returns: Player"
  [inventory]
  (.player ^Inventory inventory))

(defn menu-get-container-id
  "Get the containerId (window-id) from an AbstractContainerMenu.

  Args:
  - menu: AbstractContainerMenu instance

  Returns: int"
  [menu]
  (.containerId ^AbstractContainerMenu menu))
