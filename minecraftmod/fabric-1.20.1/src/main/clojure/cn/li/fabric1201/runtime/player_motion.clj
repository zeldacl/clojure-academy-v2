(ns cn.li.fabric1201.runtime.player-motion
  "Fabric implementation of IPlayerMotion protocol."
  (:require [cn.li.fabric1201.adapter.server-context :as server-context]
            [cn.li.mc1201.runtime.player-motion-core :as core]
            [cn.li.mcmod.platform.player-motion :as pm]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]))

(def ^:private install-guard-lock
  (Object.))

(def ^:private ^:dynamic *installed?*
  false)

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn fabric-player-motion []
  (core/create-player-motion get-server))

(defn install-player-motion! []
  (if (var-get #'*installed?*)
    (log/info "Fabric player motion already installed, skipping")
    (locking install-guard-lock
      (if (var-get #'*installed?*)
        (log/info "Fabric player motion already installed, skipping")
        (do
          (server-context/install-server-context!)
          (pm/install-player-motion! (fabric-player-motion) "Fabric player motion")
          (alter-var-root #'*installed?* (constantly true))
          (log/info "Fabric player motion installed"))))))
