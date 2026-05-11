(ns cn.li.mc1201.runtime.interop-core
  "Loader-agnostic runtime interop helpers for world/player queries.

  All operations use only vanilla MC APIs (ServerPlayer, ServerLevel)
  so this works identically on both Forge and Fabric."
  (:require [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.core BlockPos]
           [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer ServerLevel]))

(defn get-player-by-uuid
  ^ServerPlayer [^MinecraftServer server uuid-str]
  (try
    (query-core/get-player-by-uuid server uuid-str)
    (catch Exception _
      nil)))

(defn get-level-by-id
  ^ServerLevel [^MinecraftServer server world-id]
  (try
    (query-core/resolve-level-strict server world-id)
    (catch Exception _
      nil)))

(defn get-player-view
  [^MinecraftServer server player-uuid]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid server player-uuid)]
      (let [eye (.getEyePosition player)
            look (.getLookAngle player)
            world-id (some-> (.dimension (.level player)) .location str)]
        {:world-id (or world-id "minecraft:overworld")
         :x (.x eye)
         :y (.y eye)
         :z (.z eye)
         :look-x (.x look)
         :look-y (.y look)
         :look-z (.z look)}))
    (catch Exception e
      (log/warn "Failed to get player view:" (ex-message e))
      nil)))

(defn get-player-main-hand-item
  [^MinecraftServer server player-uuid]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid server player-uuid)]
      (let [stack (.getMainHandItem player)]
        (when (and stack (not (.isEmpty stack)))
          stack)))
    (catch Exception e
      (log/warn "Failed to get player main hand item:" (ex-message e))
      nil)))

(defn get-block-entity-at
  [^MinecraftServer server world-id x y z]
  (try
    (when-let [^ServerLevel level (get-level-by-id server world-id)]
      (.getBlockEntity level (BlockPos. (int x) (int y) (int z))))
    (catch Exception e
      (log/warn "Failed to get block entity:" (ex-message e))
      nil)))
