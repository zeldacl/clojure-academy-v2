(ns cn.li.forge1201.runtime.saved-locations
  "Forge implementation of ISavedLocations protocol using NBT storage."
  (:require [cn.li.mcmod.platform.saved-locations :as psl]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.nbt CompoundTag ListTag]
           [net.minecraftforge.server ServerLifecycleHooks]
           [java.util UUID]))

(defn- get-server ^MinecraftServer []
  (ServerLifecycleHooks/getCurrentServer))

(defn- get-player-by-uuid [uuid-str]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (let [uuid (UUID/fromString uuid-str)]
        (.getPlayer (.getPlayerList server) uuid)))
    (catch Exception e
      (log/warn "Failed to get player by UUID:" uuid-str (ex-message e))
      nil)))

(defn- get-locations-tag ^CompoundTag [^ServerPlayer player]
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

(defn- save-location-impl! [player-uuid location-name world-id x y z]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (let [locations-tag (get-locations-tag player)
            current-count (.size (.getAllKeys locations-tag))
            max-locations (long (power-runtime/get-max-saved-locations))]

        ;; Check if we're at the limit and this is a new location
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

(defn- delete-location-impl! [player-uuid location-name]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
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

(defn- get-location-impl [player-uuid location-name]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (let [locations-tag (get-locations-tag player)]
        (when (.contains locations-tag location-name)
          (nbt-to-location location-name (.getCompound locations-tag location-name)))))
    (catch Exception e
      (log/warn "Failed to get location:" (ex-message e))
      nil)))

(defn- list-locations-impl [player-uuid]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (let [locations-tag (get-locations-tag player)
            keys (.getAllKeys locations-tag)]
        (mapv (fn [key]
                (nbt-to-location key (.getCompound locations-tag key)))
              keys)))
    (catch Exception e
      (log/warn "Failed to list locations:" (ex-message e))
      [])))

(defn- get-location-count-impl [player-uuid]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (let [locations-tag (get-locations-tag player)]
        (.size (.getAllKeys locations-tag))))
    (catch Exception e
      (log/warn "Failed to get location count:" (ex-message e))
      0)))

(defn- has-location-impl? [player-uuid location-name]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (let [locations-tag (get-locations-tag player)]
        (.contains locations-tag location-name)))
    (catch Exception e
      (log/warn "Failed to check location:" (ex-message e))
      false)))

(defn forge-saved-locations []
  (reify psl/ISavedLocations
    (save-location! [_ player-uuid location-name world-id x y z]
      (save-location-impl! player-uuid location-name world-id x y z))
    (delete-location! [_ player-uuid location-name]
      (delete-location-impl! player-uuid location-name))
    (get-location [_ player-uuid location-name]
      (get-location-impl player-uuid location-name))
    (list-locations [_ player-uuid]
      (list-locations-impl player-uuid))
    (get-location-count [_ player-uuid]
      (get-location-count-impl player-uuid))
    (has-location? [_ player-uuid location-name]
      (has-location-impl? player-uuid location-name))))

(defn install-saved-locations! []
  (alter-var-root #'psl/*saved-locations*
                  (constantly (forge-saved-locations)))
  (log/info "Forge saved locations installed"))
