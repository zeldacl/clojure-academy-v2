(ns cn.li.mc1201.runtime.block-manipulation-core
  "Loader-agnostic block manipulation helpers.

  Uses only vanilla MC APIs. Platform adapters supply:
  - server reference
  - break-guard-fn: (fn [level pos player]) → boolean (Forge fires event; Fabric performs vanilla checks)
  - get-block-key-fn: (fn [block]) → String (BuiltInRegistries lookup, same on both loaders)

  Note: set-block and get-block use BuiltInRegistries/BLOCK directly.
  break-block! delegates the permission check to break-guard-fn supplied by the caller."
  (:require [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.core BlockPos]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer ServerLevel]
           [net.minecraft.world.item ItemStack Items]
           [net.minecraft.world.item.enchantment Enchantments]
           [net.minecraft.world.level.block Block Blocks]))

(defn- fortune-tool-stack
  ^ItemStack [fortune-level]
  (doto (ItemStack. Items/NETHERITE_PICKAXE)
    (.enchant Enchantments/BLOCK_FORTUNE (int fortune-level))))

(defn get-level-by-id
  ^ServerLevel [^MinecraftServer server world-id]
  (try
    (query-core/resolve-level-strict server world-id)
    (catch Exception e
      (log/warn "Failed to get level:" world-id (ex-message e))
      nil)))

(defn- block-key-str [^Block block]
  (str (.getKey BuiltInRegistries/BLOCK block)))

(defn break-block!
  "Break a block at [x y z] in world-id.
  break-guard-fn: (fn [^ServerLevel level ^BlockPos pos ^ServerPlayer player]) → boolean
  Forge callers pass an event-based guard; Fabric callers pass a vanilla guard."
  ([^MinecraftServer server player-uuid world-id x y z drop? break-guard-fn]
   (break-block! server player-uuid world-id x y z drop? 0 break-guard-fn))
  ([^MinecraftServer server player-uuid world-id x y z drop? fortune-level break-guard-fn]
   (try
     (when-let [^ServerLevel level (get-level-by-id server world-id)]
       (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
         (let [pos (BlockPos. (int x) (int y) (int z))]
           (when (break-guard-fn level pos player)
             (let [state (.getBlockState level pos)]
               (when drop?
                 (if (pos? (int fortune-level))
                   (Block/dropResources state level pos (.getBlockEntity level pos) player
                                        (fortune-tool-stack fortune-level))
                   (Block/dropResources state level pos)))
               ;; Play vanilla block break sound + particles (matches upstream implementation)
               (.levelEvent level 2001 pos (Block/getId state)))
             (.removeBlock level pos false)
             true))))
     (catch Exception e
       (log/warn "Failed to break block:" (ex-message e))
       false))))

(defn can-break-block?
  "Check whether a player may break a block at [x y z] in world-id.

  Uses the same shared server/player/level/position resolution as break-block!,
  while delegating the final permission decision to the platform-supplied
  break-guard-fn."
  [^MinecraftServer server player-uuid world-id x y z break-guard-fn]
  (try
    (when-let [^ServerLevel level (get-level-by-id server world-id)]
      (when-let [^ServerPlayer player (query-core/get-player-by-uuid server player-uuid)]
        (let [pos (BlockPos. (int x) (int y) (int z))]
          (boolean (break-guard-fn level pos player)))))
    (catch Exception e
      (log/warn "Failed to check can-break-block:" (ex-message e))
      false)))

(defn set-block!
  [^MinecraftServer server world-id x y z block-id]
  (try
    (when-let [^ServerLevel level (get-level-by-id server world-id)]
      (let [pos (BlockPos. (int x) (int y) (int z))
            rl (ResourceLocation. (str block-id))
            ^Block block (.get BuiltInRegistries/BLOCK rl)]
        (when block
          (.setBlock level pos (.defaultBlockState block) 3)
          true)))
    (catch Exception e
      (log/warn "Failed to set block:" (ex-message e))
      false)))

(defn get-block
  [^MinecraftServer server world-id x y z]
  (try
    (when-let [^ServerLevel level (get-level-by-id server world-id)]
      (let [pos (BlockPos. (int x) (int y) (int z))
            state (.getBlockState level pos)
            block (.getBlock state)]
        (when-not (.isAir state)
          (block-key-str block))))
    (catch Exception e
      (log/warn "Failed to get block:" (ex-message e))
      nil)))

(defn get-block-hardness
  [^MinecraftServer server world-id x y z]
  (try
    (when-let [^ServerLevel level (get-level-by-id server world-id)]
      (let [pos (BlockPos. (int x) (int y) (int z))
            state (.getBlockState level pos)]
        (.getDestroySpeed state level pos)))
    (catch Exception e
      (log/warn "Failed to get block hardness:" (ex-message e))
      nil)))

(defn find-blocks-in-line
  [^MinecraftServer server world-id x1 y1 z1 dx dy dz max-distance]
  (try
    (when-let [^ServerLevel level (get-level-by-id server world-id)]
      (let [step-size 0.5
            steps (int (/ max-distance step-size))
            results (transient [])]
        (doseq [i (range steps)]
          (let [t (* i step-size)
                x (+ x1 (* dx t))
                y (+ y1 (* dy t))
                z (+ z1 (* dz t))
                pos (BlockPos. (int x) (int y) (int z))
                state (.getBlockState level pos)
                block (.getBlock state)]
            (when-not (.isAir state)
              (conj! results
                     {:x (int x)
                      :y (int y)
                      :z (int z)
                      :block-id (block-key-str block)
                      :hardness (.getDestroySpeed state level pos)}))))
        (persistent! results)))
    (catch Exception e
      (log/warn "Failed to find blocks in line:" (ex-message e))
      [])))

(defn liquid-block?
  [^MinecraftServer server world-id x y z]
  (try
    (when-let [^ServerLevel level (get-level-by-id server world-id)]
      (let [pos (BlockPos. (int x) (int y) (int z))
            fluid-state (.getFluidState (.getBlockState level pos))]
        (and fluid-state (not (.isEmpty fluid-state)))))
    (catch Exception e
      (log/warn "Failed to check liquid block:" (ex-message e))
      false)))

(defn farmland-block?
  [^MinecraftServer server world-id x y z]
  (try
    (when-let [^ServerLevel level (get-level-by-id server world-id)]
      (let [pos (BlockPos. (int x) (int y) (int z))
            state (.getBlockState level pos)]
        (= (.getBlock state) Blocks/FARMLAND)))
    (catch Exception e
      (log/warn "Failed to check farmland block:" (ex-message e))
      false)))
