(ns cn.li.forge1201.runtime.entity-query
  "Forge-side entity query adapters for platform-neutral API."
  (:require [cn.li.mc1201.runtime.entity-query-core :as core]
            [cn.li.mcmod.platform.entity :as pentity]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn install-entity-query!
  []
  (alter-var-root #'pentity/*entity-get-type-id-fn*
                  (constantly (core/create-entity-type-id-fn #(ServerLifecycleHooks/getCurrentServer))))
  (log/info "Forge entity query installed"))

