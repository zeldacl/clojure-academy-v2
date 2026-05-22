(ns cn.li.mcmod.platform.world-effects
  "Protocol for world-level effects (lightning, explosions, entity/block queries).

  Platform (forge) implements this protocol and binds to *world-effects*.
  Game logic (ac) calls protocol methods without importing Minecraft classes.")

(defprotocol IWorldEffects
  "World-level effects and queries."

  (spawn-lightning! [this world-id x y z]
    "Spawn a lightning bolt at the given position.
    - world-id: string (dimension identifier)
    - x, y, z: double coordinates
    Returns: true if successful, false otherwise")

  (create-explosion! [this world-id x y z radius fire?]
    "Create an explosion at the given position.
    - world-id: string (dimension identifier)
    - x, y, z: double coordinates
    - radius: double explosion radius
    - fire?: boolean, whether to set blocks on fire
    Returns: true if successful, false otherwise")

  (spawn-projectile! [this world-id projectile-spec]
    "Spawn a projectile entity in world.
    - world-id: string (dimension identifier)
    - projectile-spec: map
      {:entity-id string
       :x double :y double :z double
       :vx double :vy double :vz double
       :owner-uuid string?}
    Returns: {:success? boolean :uuid string? :entity-id string?}")

  (find-entities-in-radius [this world-id x y z radius]
    "Find all living entities within radius of position.
    - world-id: string (dimension identifier)
    - x, y, z: double coordinates
    - radius: double search radius
    Returns: seq of entity maps
             {:uuid string
              :x double :y double :z double
              :width double :height double :eye-height double
              :type string}")

  (find-entities-in-aabb [this world-id min-x min-y min-z max-x max-y max-z]
    "Find all living entities within an axis-aligned bounding box.
    - world-id: string (dimension identifier)
    - min/max coordinates: double bounds
    Returns: seq of entity maps
             {:uuid string
              :x double :y double :z double
              :width double :height double :eye-height double
              :type string}")

  (find-blocks-in-radius [this world-id x y z radius block-predicate]
    "Find all blocks within radius matching predicate.
    - world-id: string (dimension identifier)
    - x, y, z: double coordinates
    - radius: double search radius
    - block-predicate: fn [block-id] -> boolean (block-id is string like \"minecraft:iron_ore\")
    Returns: seq of block maps {:x int :y int :z int :block-id string}")

  (play-sound! [this world-id x y z sound-id source volume pitch]
    "Play a world sound at position and broadcast to nearby players.
    - world-id: string dimension id
    - x, y, z: double coordinates
    - sound-id: string registry id, e.g. \"minecraft:block.anvil.destroy\"
    - source: keyword, one of :ambient :players :blocks :hostile :neutral :music :master :weather :records
    - volume: float-like
    - pitch: float-like
    Returns true if successful."))

(def ^:dynamic *world-effects*
  "Bound by platform (forge) to a reified IWorldEffects implementation.
  nil until platform init runs."
  nil)
