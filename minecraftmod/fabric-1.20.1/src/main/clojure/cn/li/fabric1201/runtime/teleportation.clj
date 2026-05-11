(ns cn.li.fabric1201.runtime.teleportation
  "Fabric thin adapter for ITeleportation protocol.
  Delegates all MC logic to mc1201 teleportation-core; obtains server reference
  from Fabric server-context."
  (:require [cn.li.mcmod.platform.teleportation :as ptp]
            [cn.li.mc1201.runtime.teleportation-core :as tc]
            [cn.li.fabric1201.runtime.server-context :as server-ctx]
            [cn.li.mcmod.util.log :as log]))

(defn fabric-teleportation []
  (reify ptp/ITeleportation
    (teleport-player! [_ player-uuid world-id x y z]
      (tc/teleport-player! (server-ctx/get-server) player-uuid world-id x y z))
    (teleport-with-entities! [_ player-uuid world-id x y z radius]
      (tc/teleport-with-entities! (server-ctx/get-server) player-uuid world-id x y z radius))
    (reset-fall-damage! [_ player-uuid]
      (tc/reset-fall-damage! (server-ctx/get-server) player-uuid))
    (get-player-position [_ player-uuid]
      (tc/get-player-position (server-ctx/get-server) player-uuid))
    (get-player-dimension [_ player-uuid]
      (tc/get-player-dimension (server-ctx/get-server) player-uuid))))

(defn install-teleportation! []
  (alter-var-root #'ptp/*teleportation*
                  (constantly (fabric-teleportation)))
  (log/info "Fabric teleportation installed"))
