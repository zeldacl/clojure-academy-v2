(ns cn.li.mcmod.platform.saved-locations
  "Protocol for saved location storage and management.

  Platform (forge) implements this protocol and binds to *saved-locations*.
  Game logic (ac) calls protocol methods without importing Minecraft classes.")

(defprotocol ISavedLocations
  "Saved location storage for teleportation."

  (save-location! [this player-uuid location-name world-id x y z]
    "Save a named location for a player.
    - player-uuid: string (player UUID)
    - location-name: string (location name, max 32 chars)
    - world-id: string (dimension identifier)
    - x, y, z: double coordinates
    Returns: true if saved successfully, false if limit reached or error")

  (delete-location! [this player-uuid location-name]
    "Delete a saved location for a player.
    - player-uuid: string (player UUID)
    - location-name: string (location name)
    Returns: true if deleted successfully, false if not found")

  (get-location [this player-uuid location-name]
    "Get a specific saved location.
    - player-uuid: string (player UUID)
    - location-name: string (location name)
    Returns: map {:name string :world-id string :x double :y double :z double} or nil")

  (list-locations [this player-uuid]
    "List all saved locations for a player.
    - player-uuid: string (player UUID)
    Returns: seq of location maps [{:name string :world-id string :x double :y double :z double}]")

  (get-location-count [this player-uuid]
    "Get the number of saved locations for a player.
    - player-uuid: string (player UUID)
    Returns: int count")

  (has-location? [this player-uuid location-name]
    "Check if a location exists.
    - player-uuid: string (player UUID)
    - location-name: string (location name)
    Returns: boolean"))

(def ^:dynamic *saved-locations*
  "Bound by platform (forge) to a reified ISavedLocations implementation.
  nil until platform init runs."
  nil)
