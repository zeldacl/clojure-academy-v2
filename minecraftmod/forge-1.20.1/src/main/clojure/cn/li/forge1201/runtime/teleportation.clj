(ns cn.li.forge1201.runtime.teleportation
  "Forge thin adapter for ITeleportation protocol.
  Delegates all MC logic to mc1201 teleportation-core; only provides
  the server reference via Forge ServerLifecycleHooks."
  (:require [cn.li.mcmod.platform.teleportation :as ptp]
            [cn.li.mc1201.runtime.teleportation-core :as tc]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn forge-teleportation []
  (tc/create-teleportation #(ServerLifecycleHooks/getCurrentServer)))

(defn install-teleportation! []
  (alter-var-root #'ptp/*teleportation*
                  (constantly (forge-teleportation)))
  (log/info "Forge teleportation installed"))
