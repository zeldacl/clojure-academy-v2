(ns cn.li.fabric1201.runtime.entity-motion
  "Fabric implementation of IEntityMotion protocol."
  (:require [cn.li.fabric1201.runtime.server-context :as server-context]
            [cn.li.mc1201.runtime.entity-motion-core :as core]
            [cn.li.mcmod.platform.entity-motion :as pem]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]))

(defonce ^:private installed? (atom false))

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn fabric-entity-motion []
  (core/create-entity-motion get-server))

(defn install-entity-motion! []
  (if-not (compare-and-set! installed? false true)
    (log/info "Fabric entity motion already installed, skipping")
    (do
      (server-context/install-server-context!)
      (alter-var-root #'pem/*entity-motion*
                      (constantly (fabric-entity-motion)))
      (log/info "Fabric entity motion installed"))))
