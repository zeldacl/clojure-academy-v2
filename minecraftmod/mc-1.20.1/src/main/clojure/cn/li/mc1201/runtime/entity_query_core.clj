(ns cn.li.mc1201.runtime.entity-query-core
  "Shared Minecraft-side entity query helpers (no loader API imports)."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [java.util UUID]
           [net.minecraft.core.registries BuiltInRegistries Registries]
           [net.minecraft.resources ResourceKey ResourceLocation]
           [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerLevel ServerPlayer]
           [net.minecraft.world.entity Entity]))

(defn get-player-by-uuid
  [^MinecraftServer server player-uuid]
  (when server
    (try
      (let [uuid (UUID/fromString (str player-uuid))]
        (.getPlayer (.getPlayerList server) uuid))
      (catch Exception _
        nil))))

(defn resolve-level
  [^MinecraftServer server world-id]
  (when server
    (if (or (nil? world-id) (= "" (str world-id)))
      (.overworld server)
      (try
        (let [rid (ResourceLocation. (str world-id))
              key (ResourceKey/create Registries/DIMENSION rid)]
          (or (.getLevel server key) (.overworld server)))
        (catch Exception _
          (.overworld server))))))

(defn resolve-level-strict
  [^MinecraftServer server world-id]
  (when (and server world-id (not= "" (str world-id)))
    (try
      (let [rid (ResourceLocation. (str world-id))
            key (ResourceKey/create Registries/DIMENSION rid)]
        (.getLevel server key))
      (catch Exception _
        nil))))

(defn get-entity-by-uuid
  [^ServerLevel level entity-uuid]
  (when level
    (try
      (.getEntity level (UUID/fromString (str entity-uuid)))
      (catch Exception _
        nil))))

(defn entity-type-id-for-entity
  [^Entity entity]
  (when entity
    (str (.getKey BuiltInRegistries/ENTITY_TYPE (.getType entity)))))

(defn entity-type-id
  [^MinecraftServer server world-id entity-uuid]
  (when-let [^ServerLevel level (resolve-level server world-id)]
    (some-> (get-entity-by-uuid level entity-uuid)
            (entity-type-id-for-entity))))

(defn create-entity-type-id-fn
  "Create a platform-neutral entity type lookup function backed by a server supplier."
  [get-server]
  (fn [world-id entity-uuid]
    (try
      (entity-type-id (get-server) world-id entity-uuid)
      (catch Exception e
        (log/warn "Failed to query entity type id:" world-id entity-uuid (ex-message e))
        nil))))
