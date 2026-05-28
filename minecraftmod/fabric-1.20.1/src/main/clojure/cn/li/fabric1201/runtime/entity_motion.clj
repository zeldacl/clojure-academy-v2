(ns cn.li.fabric1201.runtime.entity-motion
  "Fabric implementation of IEntityMotion protocol."
  (:require [cn.li.fabric1201.adapter.server-context :as server-context]
            [cn.li.mc1201.runtime.entity-motion-core :as core]
            [cn.li.mcmod.platform.entity-motion :as pem]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]))

(def ^:private install-guard-lock
  (Object.))

(def ^:private ^:dynamic *installed?*
  false)

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn fabric-entity-motion []
  (core/create-entity-motion get-server))

(defn install-entity-motion! []
  (if (var-get #'*installed?*)
    (log/info "Fabric entity motion already installed, skipping")
    (locking install-guard-lock
      (if (var-get #'*installed?*)
        (log/info "Fabric entity motion already installed, skipping")
        (do
          (server-context/install-server-context!)
          (alter-var-root #'pem/*entity-motion*
                          (constantly (fabric-entity-motion)))
          (alter-var-root #'*installed?* (constantly true))
          (log/info "Fabric entity motion installed"))))))
