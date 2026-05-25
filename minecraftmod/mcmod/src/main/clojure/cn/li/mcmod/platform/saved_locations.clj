(ns cn.li.mcmod.platform.saved-locations
  "Policy-free protocol for named world-position storage and management.

  Platform (forge) implements this protocol and binds to *saved-locations*.
  Game logic (ac) owns feature policy such as naming and max-count limits.")

(defprotocol ISavedLocations
  "Named world-position storage. Implementations should not enforce business limits."

  (save-location! [this player-uuid location-name world-id x y z]
    "Save a named location for a player.
    - player-uuid: string (player UUID)
    - location-name: string (opaque content-owned location name)
    - world-id: string (dimension identifier)
    - x, y, z: double coordinates
    Returns: true if saved successfully, false on storage error")

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
