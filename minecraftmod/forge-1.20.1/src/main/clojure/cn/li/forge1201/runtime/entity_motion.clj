(ns cn.li.forge1201.runtime.entity-motion
  "Forge implementation of IEntityMotion protocol."
  (:require [cn.li.mc1201.runtime.entity-motion-core :as core]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.forge1201.runtime.server-context :as server-context]
            [cn.li.mcmod.platform.entity-motion :as pem]
            [cn.li.mcmod.util.log :as log]))

(defn forge-entity-motion []
  (core/create-entity-motion server-context/get-server))

(defn install-entity-motion! []
  (adapter-support/install-adapter! #'pem/*entity-motion*
                                    (forge-entity-motion)
                                    "Forge entity motion"))