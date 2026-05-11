(ns cn.li.forge1201.runtime.block-manipulation
  "Forge thin adapter for IBlockManipulation protocol.
  Loader-agnostic ops delegate to mc1201 block-manipulation-core.
  Break/can-break use Forge BlockEvent$BreakEvent inline."
  (:require [cn.li.mcmod.platform.block-manipulation :as bm]
            [cn.li.mc1201.runtime.block-manipulation-core :as bmc]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server.level ServerLevel ServerPlayer]
           [net.minecraft.core BlockPos]
           [net.minecraftforge.server ServerLifecycleHooks]
           [net.minecraftforge.event.level BlockEvent$BreakEvent]
           [cn.li.forge1201.bridge ForgeRuntimeBridge]))

(defn- get-server []
  (ServerLifecycleHooks/getCurrentServer))

;; Forge-specific break guard: fires BlockEvent$BreakEvent
(defn- forge-break-guard [^ServerLevel level ^BlockPos pos ^ServerPlayer player]
  (let [state (.getBlockState level pos)
        event (BlockEvent$BreakEvent. level pos state player)]
    (ForgeRuntimeBridge/postEvent event)
    (not (.isCanceled event))))

(defn- can-break-block-impl? [player-uuid world-id x y z]
  (try
    (when-let [^ServerLevel level (bmc/get-level-by-id (get-server) world-id)]
      (when-let [^ServerPlayer player (bmc/get-player-by-uuid (get-server) player-uuid)]
        (let [pos (BlockPos. (int x) (int y) (int z))
              state (.getBlockState level pos)
              event (BlockEvent$BreakEvent. level pos state player)]
          (ForgeRuntimeBridge/postEvent event)
          (not (.isCanceled event)))))
    (catch Exception e
      (log/warn "Failed to check can-break-block:" (ex-message e))
      false)))

(defn forge-block-manipulation []
  (reify bm/IBlockManipulation
    (break-block! [_ player-id world-id x y z drop?]
      (bmc/break-block! (get-server) player-id world-id x y z drop? forge-break-guard))
    (set-block! [_ world-id x y z block-id]
      (bmc/set-block! (get-server) world-id x y z block-id))
    (get-block [_ world-id x y z]
      (bmc/get-block (get-server) world-id x y z))
    (get-block-hardness [_ world-id x y z]
      (bmc/get-block-hardness (get-server) world-id x y z))
    (can-break-block? [_ player-id world-id x y z]
      (can-break-block-impl? player-id world-id x y z))
    (find-blocks-in-line [_ world-id x1 y1 z1 dx dy dz max-distance]
      (bmc/find-blocks-in-line (get-server) world-id x1 y1 z1 dx dy dz max-distance))
    (liquid-block? [_ world-id x y z]
      (boolean (bmc/liquid-block? (get-server) world-id x y z)))
    (farmland-block? [_ world-id x y z]
      (boolean (bmc/farmland-block? (get-server) world-id x y z)))))

(defn install-block-manipulation! []
  (alter-var-root #'bm/*block-manipulation*
                  (constantly (forge-block-manipulation)))
  (log/info "Forge block manipulation installed"))
