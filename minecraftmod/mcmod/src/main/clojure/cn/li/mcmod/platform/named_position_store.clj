(ns cn.li.mcmod.platform.named-position-store
  "Policy-free protocol for content-named world-position storage.

  Platforms bind *named-position-store* to an implementation. Content logic owns
  feature policy such as naming, quotas, and user-facing semantics.")

(defprotocol INamedPositionStore
  "Content-named world-position storage. Implementations should not enforce business limits."

  (save-location! [this player-uuid location-name world-id x y z]
    "Save a named world position for a player.
    - player-uuid: string (player UUID)
    - location-name: string (opaque content-owned position name)
    - world-id: string (dimension identifier)
    - x, y, z: double coordinates
    Returns: true if saved successfully, false on storage error")

  (delete-location! [this player-uuid location-name]
    "Delete a named world position for a player.
    - player-uuid: string (player UUID)
    - location-name: string (position name)
    Returns: true if deleted successfully, false if not found")

  (get-location [this player-uuid location-name]
    "Get a specific named world position.
    - player-uuid: string (player UUID)
    - location-name: string (position name)
    Returns: map {:name string :world-id string :x double :y double :z double} or nil")

  (list-locations [this player-uuid]
    "List all named world positions for a player.
    - player-uuid: string (player UUID)
    Returns: seq of position maps [{:name string :world-id string :x double :y double :z double}]")

  (get-location-count [this player-uuid]
    "Get the number of named world positions for a player.
    - player-uuid: string (player UUID)
    Returns: int count")

  (has-location? [this player-uuid location-name]
    "Check if a named world position exists.
    - player-uuid: string (player UUID)
    - location-name: string (position name)
    Returns: boolean"))

(def ^:dynamic *named-position-store*
  "Bound by platform to a reified INamedPositionStore implementation.
  nil until platform init runs."
  nil)
