(ns cn.li.fabric1201.runtime.block-manipulation
  "Fabric thin adapter for IBlockManipulation protocol.
  Delegates to mc1201 block-manipulation-core using Fabric server-context.
  break-guard is (constantly true): Fabric has no equivalent Forge break event."
  (:require [cn.li.mcmod.platform.block-manipulation :as bm]
            [cn.li.mc1201.runtime.block-manipulation-core :as bmc]
            [cn.li.fabric1201.runtime.server-context :as server-ctx]
            [cn.li.mcmod.util.log :as log]))

(defn fabric-block-manipulation []
  (reify bm/IBlockManipulation
    (break-block! [_ player-id world-id x y z drop?]
      (bmc/break-block! (server-ctx/get-server) player-id world-id x y z drop? (constantly true)))
    (set-block! [_ world-id x y z block-id]
      (bmc/set-block! (server-ctx/get-server) world-id x y z block-id))
    (get-block [_ world-id x y z]
      (bmc/get-block (server-ctx/get-server) world-id x y z))
    (get-block-hardness [_ world-id x y z]
      (bmc/get-block-hardness (server-ctx/get-server) world-id x y z))
    (can-break-block? [_ _player-id _world-id _x _y _z]
      true)
    (find-blocks-in-line [_ world-id x1 y1 z1 dx dy dz max-distance]
      (bmc/find-blocks-in-line (server-ctx/get-server) world-id x1 y1 z1 dx dy dz max-distance))
    (liquid-block? [_ world-id x y z]
      (boolean (bmc/liquid-block? (server-ctx/get-server) world-id x y z)))
    (farmland-block? [_ world-id x y z]
      (boolean (bmc/farmland-block? (server-ctx/get-server) world-id x y z)))))

(defn install-block-manipulation! []
  (alter-var-root #'bm/*block-manipulation*
                  (constantly (fabric-block-manipulation)))
  (log/info "Fabric block manipulation installed"))
