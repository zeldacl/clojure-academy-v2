(ns cn.li.forge1201.runtime.raycast
  "Forge implementation of IRaycast protocol."
  (:require [cn.li.mc1201.runtime.raycast-core :as core]
            [cn.li.mcmod.platform.raycast :as prc]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn forge-raycast []
  (core/create-raycast #(ServerLifecycleHooks/getCurrentServer)))

(defn install-raycast! []
  (alter-var-root #'prc/*raycast*
                  (constantly (forge-raycast)))
  (log/info "Forge raycast installed"))
