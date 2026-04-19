(ns cn.li.forge1201.runtime.entity-query
  "Forge-side entity query adapters for platform-neutral API."
  (:require [cn.li.mcmod.platform.entity :as pentity]
            [cn.li.mcmod.util.log :as log])
  (:import [java.util UUID]
           [net.minecraft.core.registries BuiltInRegistries Registries]
           [net.minecraft.resources ResourceKey ResourceLocation]
           [net.minecraftforge.server ServerLifecycleHooks]))

(defn- resolve-level
  [world-id]
  (let [server (ServerLifecycleHooks/getCurrentServer)]
    (when server
      (if (or (nil? world-id) (= "" (str world-id)))
        (.overworld server)
        (try
          (let [rid (ResourceLocation. (str world-id))
                key (ResourceKey/create Registries/DIMENSION rid)]
            (or (.getLevel server key) (.overworld server)))
          (catch Exception _
            (.overworld server)))))))

(defn- entity-type-id
  [world-id entity-uuid]
  (try
    (when-let [level (resolve-level world-id)]
      (let [entity (.getEntity level (UUID/fromString (str entity-uuid)))]
        (when entity
          (str (.getKey BuiltInRegistries/ENTITY_TYPE (.getType entity))))))
    (catch Exception _
      nil)))

(defn install-entity-query!
  []
  (alter-var-root #'pentity/*entity-get-type-id-fn*
                  (constantly entity-type-id))
  (log/info "Forge entity query installed"))

