(ns cn.li.forge1201.runtime.entity-query
  "Forge-side entity query adapters for platform-neutral API."
  (:require [cn.li.mc1201.runtime.entity-query-core :as core]
            [cn.li.mcmod.platform.entity :as pentity]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn- entity-type-id
  [world-id entity-uuid]
  (try
    (core/entity-type-id (ServerLifecycleHooks/getCurrentServer) world-id entity-uuid)
    (catch Exception e
      (log/warn "Failed to query entity type id:" world-id entity-uuid (ex-message e))
      nil)))

(defn install-entity-query!
  []
  (alter-var-root #'pentity/*entity-get-type-id-fn*
                  (constantly entity-type-id))
  (log/info "Forge entity query installed"))

