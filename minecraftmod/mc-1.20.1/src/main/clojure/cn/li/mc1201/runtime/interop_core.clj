(ns cn.li.mc1201.runtime.interop-core
  "Loader-agnostic runtime interop helpers for world/player queries.

  All operations use only vanilla MC APIs (ServerPlayer, ServerLevel)
  so this works identically on both Forge and Fabric."
  (:require [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.runtime-interop :as runtime-interop]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.core BlockPos]
           [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer ServerLevel]))

(defn get-level-by-id
  ^ServerLevel [^MinecraftServer server world-id]
  (try
    (query-core/resolve-level-strict server world-id)
    (catch Exception _
      nil)))

(defn get-player-view
  [^MinecraftServer server player-uuid]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
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
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
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

(defn runtime-interop-impl
  "Create an IRuntimeInterop implementation backed by a server supplier."
  [server-fn]
  (reify runtime-interop/IRuntimeInterop
    (get-player-view [_ player-uuid]
      (get-player-view (server-fn) player-uuid))
    (get-player-main-hand-item [_ player-uuid]
      (get-player-main-hand-item (server-fn) player-uuid))
    (get-block-entity-at [_ world-id x y z]
      (get-block-entity-at (server-fn) world-id x y z))))

(defn install-runtime-interop!
  "Install canonical runtime interop var using a shared implementation."
  [label server-fn]
  (let [impl (runtime-interop-impl server-fn)]
    (alter-var-root #'runtime-interop/*runtime-interop* (constantly impl))
    (log/info (str label " runtime interop installed"))))
