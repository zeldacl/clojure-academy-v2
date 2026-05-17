(ns cn.li.fabric1201.runtime.block-manipulation
  "Fabric thin adapter for IBlockManipulation protocol.
  Delegates to mc1201 block-manipulation-core using Fabric server-context.
  break-guard uses vanilla build and spawn-protection checks."
  (:require [cn.li.mcmod.platform.block-manipulation :as bm]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.mc1201.runtime.block-manipulation-core :as bmc]
            [cn.li.fabric1201.adapter.server-context :as server-ctx]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.core BlockPos]
           [net.minecraft.server.level ServerLevel ServerPlayer]))

(defn- fabric-break-guard [^ServerLevel level ^BlockPos pos ^ServerPlayer player]
  (try
    (and (.mayBuild player)
         (not (.isEmptyBlock level pos))
         (let [server (.getServer level)]
           (if server
             (not (.isUnderSpawnProtection server level pos player))
             true)))
    (catch Exception e
      (log/warn "Fabric block break guard failed:" (ex-message e))
      false)))

(defn fabric-block-manipulation []
  (reify bm/IBlockManipulation
    (break-block! [_ player-id world-id x y z drop?]
      (bmc/break-block! (server-ctx/get-server) player-id world-id x y z drop? fabric-break-guard))
    (set-block! [_ world-id x y z block-id]
      (bmc/set-block! (server-ctx/get-server) world-id x y z block-id))
    (get-block [_ world-id x y z]
      (bmc/get-block (server-ctx/get-server) world-id x y z))
    (get-block-hardness [_ world-id x y z]
      (bmc/get-block-hardness (server-ctx/get-server) world-id x y z))
    (can-break-block? [_ player-id world-id x y z]
      (boolean (bmc/can-break-block? (server-ctx/get-server) player-id world-id x y z fabric-break-guard)))
    (find-blocks-in-line [_ world-id x1 y1 z1 dx dy dz max-distance]
      (bmc/find-blocks-in-line (server-ctx/get-server) world-id x1 y1 z1 dx dy dz max-distance))
    (liquid-block? [_ world-id x y z]
      (boolean (bmc/liquid-block? (server-ctx/get-server) world-id x y z)))
    (farmland-block? [_ world-id x y z]
      (boolean (bmc/farmland-block? (server-ctx/get-server) world-id x y z)))))

(defn install-block-manipulation! []
  (adapter-support/install-adapter! #'bm/*block-manipulation*
                                    (fabric-block-manipulation)
                                    "Fabric block manipulation"))
