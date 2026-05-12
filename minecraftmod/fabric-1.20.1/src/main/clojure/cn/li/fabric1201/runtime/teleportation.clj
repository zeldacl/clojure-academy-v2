(ns cn.li.fabric1201.runtime.teleportation
  "Fabric thin adapter for ITeleportation protocol.
  Delegates all MC logic to mc1201 teleportation-core; obtains server reference
  from Fabric server-context."
  (:require [cn.li.mcmod.platform.teleportation :as ptp]
            [cn.li.mc1201.runtime.teleportation-core :as tc]
            [cn.li.fabric1201.runtime.server-context :as server-ctx]
            [cn.li.mcmod.util.log :as log]))

(defn fabric-teleportation []
  (tc/create-teleportation server-ctx/get-server))

(defn install-teleportation! []
  (alter-var-root #'ptp/*teleportation*
                  (constantly (fabric-teleportation)))
  (log/info "Fabric teleportation installed"))
