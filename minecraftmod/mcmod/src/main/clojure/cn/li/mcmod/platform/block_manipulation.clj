(ns cn.li.mcmod.platform.block-manipulation
  "Protocol for breaking and modifying blocks.

  No Minecraft imports.")

(defprotocol IBlockManipulation
  "Protocol for breaking and modifying blocks."

  (break-block! [this player-id world-id x y z drop?]
    "Break block at position, optionally drop items.
    Returns true if successful.")

  (set-block! [this world-id x y z block-id]
    "Set block at position to new block type.
    block-id is a string like 'minecraft:stone'.
    Returns true if successful.")

  (get-block [this world-id x y z]
    "Get block at position.
    Returns block-id string like 'minecraft:stone', or nil if air/invalid.")

  (get-block-hardness [this world-id x y z]
    "Get block hardness value.
    Returns float, or -1 if unbreakable, or nil if invalid position.")

  (can-break-block? [this player-id world-id x y z]
    "Check if player can break block (permissions, protection, etc.).
    Returns boolean.")

  (find-blocks-in-line [this world-id x1 y1 z1 dx dy dz max-distance]
    "Find blocks along a line (for Groundshock propagation).
    Returns vector of {:x :y :z :block-id :hardness} maps."))

(def ^:dynamic *block-manipulation*
  "Dynamic var bound to IBlockManipulation implementation by platform layer."
  nil)
