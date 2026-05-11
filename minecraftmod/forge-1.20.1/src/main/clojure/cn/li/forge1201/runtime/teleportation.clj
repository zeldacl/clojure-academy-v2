(ns cn.li.forge1201.runtime.teleportation
  "Forge thin adapter for ITeleportation protocol.
  Delegates all MC logic to mc1201 teleportation-core; only provides
  the server reference via Forge ServerLifecycleHooks."
  (:require [cn.li.mcmod.platform.teleportation :as ptp]
            [cn.li.mc1201.runtime.teleportation-core :as tc]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn- get-server []
  (ServerLifecycleHooks/getCurrentServer))

(defn forge-teleportation []
  (reify ptp/ITeleportation
    (teleport-player! [_ player-uuid world-id x y z]
      (tc/teleport-player! (get-server) player-uuid world-id x y z))
    (teleport-with-entities! [_ player-uuid world-id x y z radius]
      (tc/teleport-with-entities! (get-server) player-uuid world-id x y z radius))
    (reset-fall-damage! [_ player-uuid]
      (tc/reset-fall-damage! (get-server) player-uuid))
    (get-player-position [_ player-uuid]
      (tc/get-player-position (get-server) player-uuid))
    (get-player-dimension [_ player-uuid]
      (tc/get-player-dimension (get-server) player-uuid))))

(defn install-teleportation! []
  (alter-var-root #'ptp/*teleportation*
                  (constantly (forge-teleportation)))
  (log/info "Forge teleportation installed"))
