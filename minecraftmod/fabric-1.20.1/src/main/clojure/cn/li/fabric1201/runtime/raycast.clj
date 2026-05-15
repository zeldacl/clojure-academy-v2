(ns cn.li.fabric1201.runtime.raycast
  "Fabric implementation of IRaycast protocol.

  Uses shared mc1201 RaycastShared geometry/hit logic and Fabric server-context."
  (:require [cn.li.fabric1201.adapter.server-context :as server-context]
            [cn.li.mc1201.runtime.raycast-core :as core]
            [cn.li.mcmod.platform.raycast :as prc]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]))

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn fabric-raycast []
  (core/create-raycast get-server))

(defn install-raycast! []
  (server-context/install-server-context!)
  (alter-var-root #'prc/*raycast*
                  (constantly (fabric-raycast)))
  (log/info "Fabric raycast installed"))
