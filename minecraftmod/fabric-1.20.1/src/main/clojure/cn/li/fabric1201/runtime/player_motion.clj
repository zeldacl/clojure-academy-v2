(ns cn.li.fabric1201.runtime.player-motion
  "Fabric implementation of IPlayerMotion protocol."
  (:require [cn.li.fabric1201.runtime.server-context :as server-context]
            [cn.li.mc1201.runtime.player-motion-core :as core]
            [cn.li.mcmod.platform.player-motion :as pm]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]))

(defonce ^:private installed? (atom false))

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn fabric-player-motion []
  (core/create-player-motion get-server))

(defn install-player-motion! []
  (if-not (compare-and-set! installed? false true)
    (log/info "Fabric player motion already installed, skipping")
    (do
      (server-context/install-server-context!)
      (alter-var-root #'pm/*player-motion*
                      (constantly (fabric-player-motion)))
      (log/info "Fabric player motion installed"))))
