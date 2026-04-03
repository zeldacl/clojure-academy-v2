(ns cn.li.mcmod.platform.teleportation
  "Protocol for teleportation mechanics.

  Platform (forge) implements this protocol and binds to *teleportation*.
  Game logic (ac) calls protocol methods without importing Minecraft classes.")

(defprotocol ITeleportation
  "Teleportation mechanics for players."

  (teleport-player! [this player-uuid world-id x y z]
    "Teleport player to position in same or different dimension.
    - player-uuid: string (player UUID)
    - world-id: string (dimension identifier, e.g., \"minecraft:overworld\", \"minecraft:the_nether\")
    - x, y, z: double coordinates
    Returns: true if teleported successfully, false otherwise")

  (teleport-with-entities! [this player-uuid world-id x y z radius]
    "Teleport player and nearby entities to position.
    - player-uuid: string (player UUID)
    - world-id: string (dimension identifier)
    - x, y, z: double coordinates
    - radius: double radius to include nearby entities
    Returns: map {:success boolean :teleported-count int}")

  (reset-fall-damage! [this player-uuid]
    "Reset player's fall damage state.
    - player-uuid: string (player UUID)
    Returns: true if reset successfully, false otherwise")

  (get-player-position [this player-uuid]
    "Get player's current position.
    - player-uuid: string (player UUID)
    Returns: map {:world-id string :x double :y double :z double} or nil if player not found")

  (get-player-dimension [this player-uuid]
    "Get player's current dimension.
    - player-uuid: string (player UUID)
    Returns: string (dimension identifier) or nil if player not found"))

(def ^:dynamic *teleportation*
  "Bound by platform (forge) to a reified ITeleportation implementation.
  nil until platform init runs."
  nil)
