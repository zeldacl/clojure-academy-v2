(ns cn.li.fabric1201.runtime.entity-motion
  "Fabric implementation of IEntityMotion protocol."
  (:require [cn.li.fabric1201.adapter.server-context :as server-context]
            [cn.li.mc1201.runtime.entity-motion-core :as core]
            [cn.li.mcmod.platform.entity-motion :as pem]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]))

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn fabric-entity-motion []
  (core/create-entity-motion get-server))

(defn install-entity-motion! []
  (install/framework-once! ::installed
    #(do
       (server-context/install-server-context!)
       (pem/install-entity-motion! (fabric-entity-motion) "Fabric entity motion")
       (log/info "Fabric entity motion installed")))
  nil)
