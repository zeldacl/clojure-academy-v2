(ns cn.li.forge1201.runtime.player-motion
  "Forge implementation of IPlayerMotion protocol."
  (:require [cn.li.mc1201.runtime.player-motion-core :as core]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.forge1201.runtime.server-context :as server-context]
            [cn.li.mcmod.platform.player-motion :as pm]
            [cn.li.mcmod.util.log :as log]))

(defn forge-player-motion []
  (core/create-player-motion server-context/get-server))

(defn install-player-motion! []
  (adapter-support/install-adapter! #'pm/*player-motion*
                                    (forge-player-motion)
                                    "Forge player motion"))
