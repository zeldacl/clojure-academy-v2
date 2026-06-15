(ns cn.li.mcmod.platform.entity
  "Platform-neutral entity utilities.

  This namespace defines a protocol for entity/player/menu operations that
  platform adapters (forge/fabric) must implement. Avoid importing any
  `net.minecraft.*` classes here so this namespace can be compiled without
  Minecraft on the classpath."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

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

  (player-get-main-hand-item-stack [player]
    "Return the raw ItemStack from the player's main hand, or nil when empty.
    Callers must use cn.li.mcmod.platform.item functions to inspect the stack.")

  (player-main-hand-placeable-block? [player]
    "Return true when player's main-hand item is a placeable block item.")

  (player-place-main-hand-block-at-hit! [player world-id x y z face]
    "Try place one block from main hand at resolved target position.
    Returns {:placed? boolean :fallback-drop? boolean :pos {:x int :y int :z int} :face keyword}.")

  (player-consume-main-hand-item! [player amount]
    "Consume amount from main-hand item unless creative. Returns true when consumed/allowed")

  (player-drop-main-hand-item-at! [player amount x y z]
    "Consume amount from main-hand item and spawn dropped item at world coordinates.
    Returns true when operation succeeds or is bypassed in creative mode")

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

(def ^:private ^:dynamic *entity-get-type-id-fn* nil)

(defn install-entity-type-id-fn!
  [f label]
  (prt/install-impl! #'*entity-get-type-id-fn* f (or label "entity-type-id")))

(defn entity-type-id-available? []
  (prt/impl-available? #'*entity-get-type-id-fn*))

(defn call-with-entity-type-id-fn [f thunk]
  (binding [*entity-get-type-id-fn* f] (thunk)))

(defn entity-get-type-id*
  [world-id entity-uuid]
  (when-let [f *entity-get-type-id-fn*]
    (some-> (f world-id entity-uuid) str)))
