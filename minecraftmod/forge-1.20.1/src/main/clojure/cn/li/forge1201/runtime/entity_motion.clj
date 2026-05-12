(ns cn.li.forge1201.runtime.entity-motion
  "Forge implementation of IEntityMotion protocol."
  (:require [cn.li.mc1201.runtime.entity-motion-core :as core]
            [cn.li.mcmod.platform.entity-motion :as pem]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn forge-entity-motion []
  (core/create-entity-motion #(ServerLifecycleHooks/getCurrentServer)))

(defn install-entity-motion! []
  (alter-var-root #'pem/*entity-motion*
                  (constantly (forge-entity-motion)))
  (log/info "Forge entity motion installed"))