(ns cn.li.mcmod.platform.raycast
  "Protocol for raycasting (line-of-sight queries).

  Platform (forge) implements this protocol and binds to *raycast*.
  Game logic (ac) calls protocol methods without importing Minecraft classes.")

(defprotocol IRaycast
  "Raycasting utilities for line-of-sight queries."

  (raycast-blocks [this world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
    "Raycast from start position in direction to find first block hit.
    - world-id: string (dimension identifier)
    - start-x, start-y, start-z: double start coordinates
    - dir-x, dir-y, dir-z: double direction vector (normalized)
    - max-distance: double maximum raycast distance
    Returns: block hit map {:x int :y int :z int :block-id string :face keyword :distance double}
             or nil if no block hit")

  (raycast-entities [this world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
    "Raycast from start position in direction to find first entity hit.
    - world-id: string (dimension identifier)
    - start-x, start-y, start-z: double start coordinates
    - dir-x, dir-y, dir-z: double direction vector (normalized)
    - max-distance: double maximum raycast distance
    Returns: entity hit map {:uuid string :x double :y double :z double :type string :distance double}
             or nil if no entity hit")

  (raycast-combined [this world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
    "Raycast from start position in direction to find first hit (block or entity).
    - world-id: string (dimension identifier)
    - start-x, start-y, start-z: double start coordinates
    - dir-x, dir-y, dir-z: double direction vector (normalized)
    - max-distance: double maximum raycast distance
    Returns: hit map with :hit-type (:block or :entity) and corresponding data
             or nil if no hit")

  (get-player-look-vector [this player-uuid]
    "Get player's current look direction vector.
    - player-uuid: string (player UUID)
    Returns: vector map {:x double :y double :z double} (normalized direction)
             or nil if player not found")

  (raycast-from-player [this player-uuid max-distance living-only?]
    "Raycast from player's eye position in look direction.
    - player-uuid: string (player UUID)
    - max-distance: double maximum raycast distance
    - living-only?: boolean, if true only hit living entities
    Returns: entity hit map {:entity-id string :x double :y double :z double :distance double}
             or nil if no entity hit"))

(def ^:dynamic *raycast*
  "Bound by platform (forge) to a reified IRaycast implementation.
  nil until platform init runs."
  nil)
