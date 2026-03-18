(ns my-mod.platform.world
  "Platform-agnostic world access abstraction layer.
  
  This namespace provides protocols for world/level operations without
  depending on specific Minecraft versions (World class renamed to Level
  in 1.17+, method names changed across versions).
  
  Design:
  - World objects are passed from platform layer (not created in core)
  - Protocols extended directly to Minecraft's World/Level classes
  - Core code uses protocol methods instead of direct Java calls
  - Enables cross-version compatibility")

;; ============================================================================
;; World Access Protocol
;; ============================================================================

(defprotocol IWorldAccess
  "Protocol for world/level access operations.
  
  Platform implementations extend this to World (1.16) or Level (1.17+) classes."
  
  (world-get-tile-entity [this pos]
    "Get tile entity (block entity) at position.
    
    Args:
    - pos: IBlockPos
    
    Returns: TileEntity/BlockEntity or nil if none exists")
  
  (world-get-block-state [this pos]
    "Get block state at position.
    
    Args:
    - pos: IBlockPos
    
    Returns: BlockState")
  
  (world-set-block [this pos state flags]
    "Set block at position.
    
    Args:
    - pos: IBlockPos
    - state: BlockState
    - flags: int - block update flags
    
    Returns: boolean - true if successful")

  (world-remove-block [this pos]
    "Remove block at position without drops.

    Args:
    - pos: IBlockPos

    Returns: boolean - true if successful")

  (world-place-block-by-id [this block-id pos flags]
    "Place block by DSL block-id using platform registry lookup.

    Args:
    - block-id: string - DSL block-id
    - pos: IBlockPos
    - flags: int - block update flags

    Returns: boolean - true if successful")
  
  (world-is-chunk-loaded? [this chunk-x chunk-z]
    "Check if chunk at coordinates is loaded.

    Args:
    - chunk-x: int - chunk X coordinate
    - chunk-z: int - chunk Z coordinate

    Returns: boolean")

  (world-get-day-time [this]
    "Get the current day time (0-24000).

    Returns: long - current time in ticks")

  (world-is-raining [this]
    "Check if it is currently raining.

    Returns: boolean")

  (world-is-client-side [this]
    "Check if this is the client side.

    Returns: boolean")

  (world-can-see-sky [this pos]
    "Check if position can see the sky.

    Args:
    - pos: IBlockPos

    Returns: boolean"))

;; ============================================================================
;; Chunk Utilities
;; ============================================================================

(defn block-to-chunk-coord
  "Convert block coordinate to chunk coordinate.
  
  Minecraft chunks are 16x16, so divide by 16 (right shift 4 bits)."
  [block-coord]
  (bit-shift-right block-coord 4))

(defn is-chunk-loaded-at-block?
  "Check if chunk containing block position is loaded.
  
  Args:
  - world: IWorldAccess
  - x: int - block X coordinate
  - z: int - block Z coordinate
  
  Returns: boolean"
  [world x z]
  (let [chunk-x (block-to-chunk-coord x)
        chunk-z (block-to-chunk-coord z)]
    (world-is-chunk-loaded? world chunk-x chunk-z)))
