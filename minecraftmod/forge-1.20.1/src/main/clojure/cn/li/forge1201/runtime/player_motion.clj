(ns cn.li.forge1201.runtime.player-motion
  "Forge implementation of IPlayerMotion protocol."
  (:require [cn.li.mc1201.runtime.player-motion-core :as core]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.mcmod.platform.player-motion :as pm]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn forge-player-motion []
  (core/create-player-motion #(ServerLifecycleHooks/getCurrentServer)))

(defn install-player-motion! []
  (adapter-support/install-adapter! #'pm/*player-motion*
                                    (forge-player-motion)
                                    "Forge player motion"))
