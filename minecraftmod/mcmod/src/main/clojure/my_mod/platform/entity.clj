(ns my-mod.platform.entity
  "Platform-neutral entity utilities."
  (:import [net.minecraft.world.entity Entity]
           [net.minecraft.world.entity.player Player]))

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
