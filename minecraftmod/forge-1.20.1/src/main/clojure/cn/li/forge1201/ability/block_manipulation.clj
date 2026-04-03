(ns cn.li.forge1201.ability.block-manipulation
  "Forge implementation of IBlockManipulation protocol."
  (:require [cn.li.mcmod.platform.block-manipulation :as bm]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerPlayer ServerLevel]
           [net.minecraft.world.level Level]
           [net.minecraft.world.level.block Block]
           [net.minecraft.core BlockPos]
           [net.minecraft.resources ResourceLocation]
           [net.minecraftforge.server ServerLifecycleHooks]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.registries ForgeRegistries]
           [net.minecraftforge.event.level BlockEvent$BreakEvent]
           [java.util UUID]))

(set! *warn-on-reflection* true)

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

(defn- get-level-by-id [world-id]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (let [res-loc (ResourceLocation. world-id)]
        (.getLevel server res-loc)))
    (catch Exception e
      (log/warn "Failed to get level:" world-id (ex-message e))
      nil)))

(defn- break-block-impl! [player-uuid world-id x y z drop?]
  (try
    (when-let [^ServerLevel level (get-level-by-id world-id)]
      (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
        (let [pos (BlockPos. (int x) (int y) (int z))
              state (.getBlockState level pos)
              block (.getBlock state)]

          ;; Fire break event to check permissions
          (let [event (BlockEvent$BreakEvent. level pos state player)]
            (.post (MinecraftForge/EVENT_BUS) event)

            (when-not (.isCanceled event)
              (when drop?
                (Block/dropResources state level pos))
              (.removeBlock level pos false)
              true)))))
    (catch Exception e
      (log/warn "Failed to break block:" (ex-message e))
      false)))

(defn- set-block-impl! [world-id x y z block-id]
  (try
    (when-let [^ServerLevel level (get-level-by-id world-id)]
      (let [pos (BlockPos. (int x) (int y) (int z))
            res-loc (ResourceLocation. block-id)
            block (.getValue ForgeRegistries/BLOCKS res-loc)]
        (when block
          (.setBlock level pos (.defaultBlockState block) 3)
          true)))
    (catch Exception e
      (log/warn "Failed to set block:" (ex-message e))
      false)))

(defn- get-block-impl [world-id x y z]
  (try
    (when-let [^ServerLevel level (get-level-by-id world-id)]
      (let [pos (BlockPos. (int x) (int y) (int z))
            state (.getBlockState level pos)
            block (.getBlock state)]
        (when-not (.isAir state)
          (str (.getKey ForgeRegistries/BLOCKS block)))))
    (catch Exception e
      (log/warn "Failed to get block:" (ex-message e))
      nil)))

(defn- get-block-hardness-impl [world-id x y z]
  (try
    (when-let [^ServerLevel level (get-level-by-id world-id)]
      (let [pos (BlockPos. (int x) (int y) (int z))
            state (.getBlockState level pos)]
        (.getDestroySpeed state level pos)))
    (catch Exception e
      (log/warn "Failed to get block hardness:" (ex-message e))
      nil)))

(defn- can-break-block-impl? [player-uuid world-id x y z]
  (try
    (when-let [^ServerLevel level (get-level-by-id world-id)]
      (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
        (let [pos (BlockPos. (int x) (int y) (int z))
              state (.getBlockState level pos)]
          ;; Fire break event to check permissions
          (let [event (BlockEvent$BreakEvent. level pos state player)]
            (.post (MinecraftForge/EVENT_BUS) event)
            (not (.isCanceled event))))))
    (catch Exception e
      (log/warn "Failed to check can-break-block:" (ex-message e))
      false)))

(defn- find-blocks-in-line-impl [world-id x1 y1 z1 dx dy dz max-distance]
  (try
    (when-let [^ServerLevel level (get-level-by-id world-id)]
      (let [step-size 0.5
            steps (int (/ max-distance step-size))
            results (atom [])]
        (doseq [i (range steps)]
          (let [t (* i step-size)
                x (+ x1 (* dx t))
                y (+ y1 (* dy t))
                z (+ z1 (* dz t))
                pos (BlockPos. (int x) (int y) (int z))
                state (.getBlockState level pos)
                block (.getBlock state)]
            (when-not (.isAir state)
              (swap! results conj
                     {:x (int x)
                      :y (int y)
                      :z (int z)
                      :block-id (str (.getKey ForgeRegistries/BLOCKS block))
                      :hardness (.getDestroySpeed state level pos)}))))
        @results))
    (catch Exception e
      (log/warn "Failed to find blocks in line:" (ex-message e))
      [])))

(defn forge-block-manipulation []
  (reify bm/IBlockManipulation
    (break-block! [_ player-id world-id x y z drop?]
      (break-block-impl! player-id world-id x y z drop?))
    (set-block! [_ world-id x y z block-id]
      (set-block-impl! world-id x y z block-id))
    (get-block [_ world-id x y z]
      (get-block-impl world-id x y z))
    (get-block-hardness [_ world-id x y z]
      (get-block-hardness-impl world-id x y z))
    (can-break-block? [_ player-id world-id x y z]
      (can-break-block-impl? player-id world-id x y z))
    (find-blocks-in-line [_ world-id x1 y1 z1 dx dy dz max-distance]
      (find-blocks-in-line-impl world-id x1 y1 z1 dx dy dz max-distance))))

(defn install-block-manipulation! []
  (alter-var-root #'bm/*block-manipulation*
                  (constantly (forge-block-manipulation)))
  (log/info "Forge block manipulation installed"))
