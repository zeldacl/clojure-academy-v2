(ns cn.li.fabric1201.runtime.entity-query
  "Fabric-side entity query adapters for platform-neutral API."
  (:require [cn.li.fabric1201.runtime.server-context :as server-context]
            [cn.li.mc1201.runtime.entity-query-core :as core]
            [cn.li.mcmod.platform.entity :as pentity]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]))

(defonce ^:private installed? (atom false))

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn- entity-type-id
  [world-id entity-uuid]
  (try
    (core/entity-type-id (get-server) world-id entity-uuid)
    (catch Exception e
      (log/warn "[fabric] Failed to query entity type id:" world-id entity-uuid (ex-message e))
      nil)))

(defn install-entity-query!
  []
  (if-not (compare-and-set! installed? false true)
    (log/info "Fabric entity query already installed, skipping")
    (do
      (server-context/install-server-context!)
      (alter-var-root #'pentity/*entity-get-type-id-fn*
                      (constantly entity-type-id))
      (log/info "Fabric entity query installed"))))
