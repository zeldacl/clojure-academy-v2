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

  (find-entities-in-radius [this world-id x y z radius]
    "Find all living entities within radius of position.
    - world-id: string (dimension identifier)
    - x, y, z: double coordinates
    - radius: double search radius
    Returns: seq of entity maps {:uuid string :x double :y double :z double :type string}")

  (find-blocks-in-radius [this world-id x y z radius block-predicate]
    "Find all blocks within radius matching predicate.
    - world-id: string (dimension identifier)
    - x, y, z: double coordinates
    - radius: double search radius
    - block-predicate: fn [block-id] -> boolean (block-id is string like \"minecraft:iron_ore\")
    Returns: seq of block maps {:x int :y int :z int :block-id string}"))

(def ^:dynamic *world-effects*
  "Bound by platform (forge) to a reified IWorldEffects implementation.
  nil until platform init runs."
  nil)
