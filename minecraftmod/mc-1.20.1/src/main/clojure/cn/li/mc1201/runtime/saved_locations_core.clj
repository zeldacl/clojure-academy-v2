(ns cn.li.mc1201.runtime.saved-locations-core
  "Loader-agnostic saved location NBT helpers.

  All functions take a MinecraftServer instance (passed by the platform adapter)
  rather than using loader-specific lifecycle hooks.
  NBT storage format: CompoundTag keyed 'SavedLocations' on player persistent data."
  (:require [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.saved-locations :as psl]
            [cn.li.mcmod.runtime.hooks-core :as power-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.nbt CompoundTag]))

(defn get-player-by-uuid
  ^ServerPlayer [^MinecraftServer server uuid-str]
  (try
    (query-core/get-player-by-uuid server uuid-str)
    (catch Exception e
      (log/warn "Failed to get player by UUID:" uuid-str (ex-message e))
      nil)))

(defn- get-locations-tag
  ^CompoundTag [^ServerPlayer player]
  (let [persistent-data (.getPersistentData player)]
    (if (.contains persistent-data "SavedLocations")
      (.getCompound persistent-data "SavedLocations")
      (let [new-tag (CompoundTag.)]
        (.put persistent-data "SavedLocations" new-tag)
        new-tag))))

(defn- location-to-nbt [world-id x y z]
  (let [tag (CompoundTag.)]
    (.putString tag "world" world-id)
    (.putDouble tag "x" x)
    (.putDouble tag "y" y)
    (.putDouble tag "z" z)
    tag))

(defn- nbt-to-location [location-name ^CompoundTag tag]
  {:name location-name
   :world-id (.getString tag "world")
   :x (.getDouble tag "x")
   :y (.getDouble tag "y")
   :z (.getDouble tag "z")})

(defn save-location!
  [^MinecraftServer server player-uuid location-name world-id x y z]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid server player-uuid)]
      (let [locations-tag (get-locations-tag player)
            current-count (.size (.getAllKeys locations-tag))
            max-locations (long (power-runtime/get-max-saved-locations))]
        (if (and (>= current-count max-locations)
                 (not (.contains locations-tag location-name)))
          (do
            (log/debug "SavedLocations: Limit reached for player" player-uuid)
            false)
          (do
            (.put locations-tag location-name (location-to-nbt world-id x y z))
            (log/debug "SavedLocations: Saved location" location-name "for player" player-uuid)
            true))))
    (catch Exception e
      (log/warn "Failed to save location:" (ex-message e))
      false)))

(defn delete-location!
  [^MinecraftServer server player-uuid location-name]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid server player-uuid)]
      (let [locations-tag (get-locations-tag player)]
        (if (.contains locations-tag location-name)
          (do
            (.remove locations-tag location-name)
            (log/debug "SavedLocations: Deleted location" location-name)
            true)
          false)))
    (catch Exception e
      (log/warn "Failed to delete location:" (ex-message e))
      false)))

(defn get-location
  [^MinecraftServer server player-uuid location-name]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid server player-uuid)]
      (let [locations-tag (get-locations-tag player)]
        (when (.contains locations-tag location-name)
          (nbt-to-location location-name (.getCompound locations-tag location-name)))))
    (catch Exception e
      (log/warn "Failed to get location:" (ex-message e))
      nil)))

(defn list-locations
  [^MinecraftServer server player-uuid]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid server player-uuid)]
      (let [locations-tag (get-locations-tag player)
            keys (.getAllKeys locations-tag)]
        (mapv (fn [key]
                (nbt-to-location key (.getCompound locations-tag key)))
              keys)))
    (catch Exception e
      (log/warn "Failed to list locations:" (ex-message e))
      [])))

(defn get-location-count
  [^MinecraftServer server player-uuid]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid server player-uuid)]
      (let [locations-tag (get-locations-tag player)]
        (.size (.getAllKeys locations-tag))))
    (catch Exception e
      (log/warn "Failed to get location count:" (ex-message e))
      0)))

(defn has-location?
  [^MinecraftServer server player-uuid location-name]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid server player-uuid)]
      (let [locations-tag (get-locations-tag player)]
        (.contains locations-tag location-name)))
    (catch Exception e
      (log/warn "Failed to check location:" (ex-message e))
      false)))

(defn create-saved-locations
  "Create an ISavedLocations adapter using a platform-provided server supplier."
  [get-server]
  (reify psl/ISavedLocations
    (save-location! [_ player-uuid location-name world-id x y z]
      (save-location! (get-server) player-uuid location-name world-id x y z))
    (delete-location! [_ player-uuid location-name]
      (delete-location! (get-server) player-uuid location-name))
    (get-location [_ player-uuid location-name]
      (get-location (get-server) player-uuid location-name))
    (list-locations [_ player-uuid]
      (list-locations (get-server) player-uuid))
    (get-location-count [_ player-uuid]
      (get-location-count (get-server) player-uuid))
    (has-location? [_ player-uuid location-name]
      (has-location? (get-server) player-uuid location-name))))
