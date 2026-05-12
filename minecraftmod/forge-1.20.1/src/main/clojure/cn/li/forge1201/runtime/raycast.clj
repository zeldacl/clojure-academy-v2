(ns cn.li.forge1201.runtime.raycast
  "Forge implementation of IRaycast protocol."
  (:require [cn.li.mc1201.runtime.raycast-core :as core]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.forge1201.runtime.server-context :as server-context]
            [cn.li.mcmod.platform.raycast :as prc]
            [cn.li.mcmod.util.log :as log]))

(defn forge-raycast []
  (core/create-raycast server-context/get-server))

(defn install-raycast! []
  (adapter-support/install-adapter! #'prc/*raycast*
                                    (forge-raycast)
                                    "Forge raycast"))
