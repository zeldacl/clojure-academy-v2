(ns cn.li.forge1201.runtime.entity-query
  "Forge-side entity query adapters for platform-neutral API."
  (:require [cn.li.mc1201.runtime.entity-query-core :as core]
            [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.forge1201.runtime.server-context :as server-context]
            [cn.li.mcmod.platform.entity :as pentity]))

(defn install-entity-query!
  []
  (adapter-support/install-adapter! #'pentity/*entity-get-type-id-fn*
                                    (core/create-entity-type-id-fn server-context/get-server)
                                    "Forge entity query"))

