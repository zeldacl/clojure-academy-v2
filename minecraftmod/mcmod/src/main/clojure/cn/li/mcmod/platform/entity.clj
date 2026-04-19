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

  (player-get-main-hand-item-id [player]
    "Return registry ID of main-hand item as string (namespace:path), or nil when empty")

  (player-get-main-hand-item-count [player]
    "Return stack count of player's main-hand item. Returns 0 when empty")

  (player-consume-main-hand-item! [player amount]
    "Consume amount from main-hand item unless creative. Returns true when consumed/allowed")

  (player-count-item-by-id [player item-id]
    "Count all items with registry id `item-id` in player's inventory. Returns int.")

  (player-consume-item-by-id! [player item-id amount]
    "Consume `amount` items with registry id `item-id` from player's inventory. Returns boolean.")

  (player-give-item-stack! [player item-stack]
    "Insert `item-stack` into player's inventory, dropping overflow in world. Returns boolean.")

  (player-spawn-entity-by-id! [player entity-id speed]
    "Spawn an entity by registry id (`namespace:path`) from player's viewpoint. Returns boolean.")

  (player-raytrace-block [player reach fluid-source-only?]
    "Raytrace block hit from player view.
    Returns nil or map {:hit-pos {:x :y :z} :place-pos {:x :y :z} :block-id string}.")

  (player-get-container-menu [player]
    "Return the player's open container/menu")

  (inventory-get-player [inventory]
    "Return the Player owning the Inventory")

  (menu-get-container-id [menu]
    "Return the containerId (window id) from an AbstractContainerMenu"))

(defonce ^{:dynamic true
           :doc "Optional platform resolver: (fn [world-id entity-uuid] -> string|nil).

  Returns canonical entity type id such as \"minecraft:creeper\"."}
  *entity-get-type-id-fn*
  nil)

(defn entity-get-type-id*
  [world-id entity-uuid]
  (when-let [f *entity-get-type-id-fn*]
    (some-> (f world-id entity-uuid) str)))
