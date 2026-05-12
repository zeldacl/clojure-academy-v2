(ns cn.li.forge1201.runtime.entity-query
  "Forge-side entity query adapters for platform-neutral API."
  (:require [cn.li.mc1201.runtime.entity-query-core :as core]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.mcmod.platform.entity :as pentity])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn install-entity-query!
  []
  (adapter-support/install-adapter! #'pentity/*entity-get-type-id-fn*
                                    (core/create-entity-type-id-fn #(ServerLifecycleHooks/getCurrentServer))
                                    "Forge entity query"))

