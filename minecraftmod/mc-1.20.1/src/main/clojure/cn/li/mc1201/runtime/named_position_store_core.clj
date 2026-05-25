(ns cn.li.mc1201.runtime.named-position-store-core
  "Loader-agnostic named world-position NBT helpers.

  All functions take a MinecraftServer instance (passed by the platform adapter)
  rather than using loader-specific lifecycle hooks. NBT storage location is
  selected by content-owned persistence descriptors."
  (:require [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mc1201.reflect-util :as ru]
            [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mcmod.platform.named-position-store :as position-store]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.nbt CompoundTag]))

(defn- nbt-store-key
  []
  (->> (power-runtime/list-player-persistence-descriptors)
       (filter #(and (= :named-world-position-store (:host-key %))
                     (= :compound-tag (:format %))
                     (:nbt-key %)))
       (sort-by (juxt #(long (or (:order %) 0)) (comp str :id)))
       first
       :nbt-key))

(defn- get-positions-tag
  ^CompoundTag [^ServerPlayer player]
  (when-let [store-key (nbt-store-key)]
    (let [^CompoundTag persistent-data (ru/inst player "getPersistentData")]
      (if (.contains persistent-data store-key)
        (.getCompound persistent-data store-key)
        (let [new-tag (CompoundTag.)]
          (.put persistent-data store-key new-tag)
          new-tag)))))

(defn- position-to-nbt [world-id x y z]
  (let [tag (CompoundTag.)]
    (.putString tag "world" world-id)
    (.putDouble tag "x" x)
    (.putDouble tag "y" y)
    (.putDouble tag "z" z)
    tag))

(defn- nbt-to-position [location-name ^CompoundTag tag]
  {:name location-name
   :world-id (.getString tag "world")
   :x (.getDouble tag "x")
   :y (.getDouble tag "y")
   :z (.getDouble tag "z")})

(defn save-location!
  [^MinecraftServer server player-uuid location-name world-id x y z]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
      (when-let [positions-tag (get-positions-tag player)]
        (.put positions-tag location-name (position-to-nbt world-id x y z))
        (log/debug "Named world position: saved" location-name "for player" player-uuid)
        true))
    (catch Exception e
      (log/warn "Failed to save named world position:" (ex-message e))
      false)))

(defn delete-location!
  [^MinecraftServer server player-uuid location-name]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
      (when-let [positions-tag (get-positions-tag player)]
        (if (.contains positions-tag location-name)
          (do
            (.remove positions-tag location-name)
            (log/debug "Named world position: deleted" location-name)
            true)
          false)))
    (catch Exception e
      (log/warn "Failed to delete named world position:" (ex-message e))
      false)))

(defn get-location
  [^MinecraftServer server player-uuid location-name]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
      (when-let [positions-tag (get-positions-tag player)]
        (when (.contains positions-tag location-name)
          (nbt-to-position location-name (.getCompound positions-tag location-name)))))
    (catch Exception e
      (log/warn "Failed to get named world position:" (ex-message e))
      nil)))

(defn list-locations
  [^MinecraftServer server player-uuid]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
      (when-let [positions-tag (get-positions-tag player)]
        (let [keys (.getAllKeys positions-tag)]
          (mapv (fn [key]
                  (nbt-to-position key (.getCompound positions-tag key)))
                keys))))
    (catch Exception e
      (log/warn "Failed to list named world positions:" (ex-message e))
      [])))

(defn get-location-count
  [^MinecraftServer server player-uuid]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
      (when-let [positions-tag (get-positions-tag player)]
        (.size (.getAllKeys positions-tag))))
    (catch Exception e
      (log/warn "Failed to get named world position count:" (ex-message e))
      0)))

(defn has-location?
  [^MinecraftServer server player-uuid location-name]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
      (when-let [positions-tag (get-positions-tag player)]
        (.contains positions-tag location-name)))
    (catch Exception e
      (log/warn "Failed to check named world position:" (ex-message e))
      false)))

(defn create-named-position-store
  "Create an INamedPositionStore adapter using a platform-provided server supplier."
  [get-server]
  (reify position-store/INamedPositionStore
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
