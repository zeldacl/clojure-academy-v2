(ns cn.li.forge1201.runtime.teleportation
  "Forge thin adapter for ITeleportation protocol.
  Delegates all MC logic to mc1201 teleportation-core; only provides
  the server reference via Forge ServerLifecycleHooks."
  (:require [cn.li.mcmod.platform.teleportation :as ptp]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.mc1201.runtime.teleportation-core :as tc]
            [cn.li.forge1201.runtime.server-context :as server-context]
            [cn.li.mcmod.util.log :as log]))

(defn forge-teleportation []
  (tc/create-teleportation server-context/get-server))

(defn install-teleportation! []
  (adapter-support/install-adapter! #'ptp/*teleportation*
                                    (forge-teleportation)
                                    "Forge teleportation"))
