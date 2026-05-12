(ns cn.li.forge1201.runtime.player-motion
  "Forge implementation of IPlayerMotion protocol."
  (:require [cn.li.mc1201.runtime.player-motion-core :as core]
            [cn.li.mcmod.platform.player-motion :as pm]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn forge-player-motion []
  (core/create-player-motion #(ServerLifecycleHooks/getCurrentServer)))

(defn install-player-motion! []
  (alter-var-root #'pm/*player-motion*
                  (constantly (forge-player-motion)))
  (log/info "Forge player motion installed"))
