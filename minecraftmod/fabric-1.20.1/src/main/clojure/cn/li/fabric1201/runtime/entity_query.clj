(ns cn.li.fabric1201.runtime.entity-query
  "Fabric-side entity query adapters for platform-neutral API."
  (:require [cn.li.fabric1201.adapter.server-context :as server-context]
            [cn.li.mc1201.runtime.entity-query-core :as core]
            [cn.li.mcmod.platform.entity :as pentity]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]))

(def ^:private install-guard-lock
  (Object.))

(def ^:private ^:dynamic *installed?*
  false)

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn install-entity-query!
  []
  (if (var-get #'*installed?*)
    (log/info "Fabric entity query already installed, skipping")
    (locking install-guard-lock
      (if (var-get #'*installed?*)
        (log/info "Fabric entity query already installed, skipping")
        (do
          (server-context/install-server-context!)
          (pentity/install-entity-type-id-fn!
            (core/create-entity-type-id-fn get-server)
            "Fabric entity query")
          (alter-var-root #'*installed?* (constantly true))
          (log/info "Fabric entity query installed"))))))
