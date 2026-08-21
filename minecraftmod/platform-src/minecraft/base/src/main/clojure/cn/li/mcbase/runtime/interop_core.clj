(ns cn.li.mcbase.runtime.interop-core
  "Loader-agnostic runtime interop helpers for world/player queries.

  All operations use only vanilla MC APIs (ServerPlayer, ServerLevel)
  so this works identically on both Forge and Fabric."
  (:require [cn.li.mcbase.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcver McAccess]
           [net.minecraft.core BlockPos]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer ServerLevel]
           [net.minecraft.world.item ItemStack]))

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
            world-id (some-> (.level player) McAccess/dimensionId)]
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

(defn- item-registry-id [^ItemStack stack]
  (when-let [registry-name (.getKey BuiltInRegistries/ITEM (.getItem stack))]
    (str (.getNamespace registry-name) ":" (.getPath registry-name))))

(defn main-hand-item-snapshot
  "Neutral `{:item-id :count}` snapshot of player-uuid's main-hand stack, or
   nil when empty. Never returns the raw ItemStack -- callers on the neutral
   side must not see a Minecraft object."
  [^MinecraftServer server player-uuid]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
      (let [^ItemStack stack (.getMainHandItem player)]
        (when (and stack (not (.isEmpty stack)))
          {:item-id (item-registry-id stack) :count (.getCount stack)})))
    (catch Exception e
      (log/warn "Failed to get player main hand item snapshot:" (ex-message e))
      nil)))

(defn consume-main-hand-item!
  "Shrink player-uuid's main-hand stack by count. Returns `{:consumed n}`
   when the stack held at least count, or nil (no mutation) otherwise."
  [^MinecraftServer server player-uuid count]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
      (let [^ItemStack stack (.getMainHandItem player)
            count (long count)]
        (when (and stack (not (.isEmpty stack)) (>= (.getCount stack) count))
          (.shrink stack (int count))
          {:consumed count})))
    (catch Exception e
      (log/warn "Failed to consume player main hand item:" (ex-message e))
      nil)))

(defn drop-main-hand-item-at!
  [^MinecraftServer server player-uuid count x y z]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
      (boolean (entity/player-drop-main-hand-item-at! player (long count) (double x) (double y) (double z))))
    (catch Exception e
      (log/warn "Failed to drop player main hand item:" (ex-message e))
      nil)))

(defn spawn-main-hand-item-copy-at!
  [^MinecraftServer server player-uuid count x y z]
  (try
    (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
      (boolean (entity/player-spawn-main-hand-item-copy-at! player (long count)
                                                               (double x) (double y) (double z))))
    (catch Exception e
      (log/warn "Failed to spawn main hand item copy:" (ex-message e))
      nil)))

(defn get-player-entity
  "Resolve the live ServerPlayer for player-uuid, for callers (e.g. tick-driven
  entity-spawn visuals) that only have a player-id and no player-ref -- the
  generic skill-callback dispatch's positional player-ref argument is never
  populated for server-tick-driven contexts (see context-manager's
  tick-context-entry!, which omits :player from the tick payload)."
  [^MinecraftServer server player-uuid]
  (try
    (query-core/get-player-by-uuid server player-uuid)
    (catch Exception e
      (log/warn "Failed to get player entity:" (ex-message e))
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
  {:get-player-view (fn [player-uuid]
                      (get-player-view (server-fn) player-uuid))
   :get-player-main-hand-item (fn [player-uuid]
                                (get-player-main-hand-item (server-fn) player-uuid))
   :main-hand-item-snapshot (fn [player-uuid]
                              (main-hand-item-snapshot (server-fn) player-uuid))
   :consume-main-hand-item! (fn [player-uuid count]
                              (consume-main-hand-item! (server-fn) player-uuid count))
   :drop-main-hand-item-at! (fn [player-uuid count x y z]
                              (drop-main-hand-item-at! (server-fn) player-uuid count x y z))
   :spawn-main-hand-item-copy-at! (fn [player-uuid count x y z]
                                    (spawn-main-hand-item-copy-at! (server-fn) player-uuid count x y z))
   :get-block-entity-at (fn [world-id x y z]
                          (get-block-entity-at (server-fn) world-id x y z))
   :get-player-entity (fn [player-uuid]
                        (get-player-entity (server-fn) player-uuid))})

(defn install-runtime-interop!
  "Install canonical runtime interop using a shared implementation."
  [label server-fn]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/install-adapter! fw-atom :runtime-interop (runtime-interop-impl server-fn)))
  nil)
