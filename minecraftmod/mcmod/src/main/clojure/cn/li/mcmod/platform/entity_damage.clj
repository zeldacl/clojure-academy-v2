(ns cn.li.mcmod.platform.entity-damage
  "Protocol for entity damage application.

  Platform (forge) implements this protocol and binds to *entity-damage*.
  Game logic (ac) calls protocol methods without importing Minecraft classes.")

(defprotocol IEntityDamage
  "Entity damage application."

  (apply-direct-damage! [this world-id entity-uuid damage source-type]
    "Apply direct damage to a single entity.
    - world-id: string (dimension identifier)
    - entity-uuid: string (entity UUID)
    - damage: double damage amount
    - source-type: keyword (:magic :lightning :explosion :generic)
    Returns: true if damage applied, false if entity not found or immune")

  (apply-aoe-damage! [this world-id x y z radius damage source-type falloff?]
    "Apply AOE damage to all entities within radius.
    - world-id: string (dimension identifier)
    - x, y, z: double coordinates (center of AOE)
    - radius: double AOE radius
    - damage: double base damage amount
    - source-type: keyword (:magic :lightning :explosion :generic)
    - falloff?: boolean, if true damage decreases with distance
    Returns: seq of damaged entity UUIDs")

  (apply-reflection-damage! [this world-id entity-uuid damage source-type reflection-count max-reflections]
    "Apply damage with reflection tracking (bounces to nearby entities).
    - world-id: string (dimension identifier)
    - entity-uuid: string (initial target UUID)
    - damage: double damage amount
    - source-type: keyword (:magic :lightning :explosion :generic)
    - reflection-count: int current reflection depth
    - max-reflections: int maximum reflection bounces
    Returns: seq of hit entity UUIDs (including reflections)"))

(def ^:dynamic *entity-damage*
  "Bound by platform (forge) to a reified IEntityDamage implementation.
  nil until platform init runs."
  nil)
