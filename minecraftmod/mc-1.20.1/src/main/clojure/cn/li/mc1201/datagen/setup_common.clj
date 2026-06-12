(ns cn.li.mc1201.datagen.setup-common
  "Shared datagen setup utilities, platform-independent.
   
   Provides common content initialization for both Forge and Fabric datagen phases."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.content :as mc-content]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.mcmod.datagen.metadata :as datagen-metadata]))

(defn- snapshot-counts
  []
  {:items (count (registry-metadata/get-all-item-ids))
   :blocks (count (registry-metadata/get-all-block-ids))
   :recipes (count (datagen-metadata/get-recipes))})

(defn- populated?
  [{:keys [items blocks recipes]}]
  (or (pos? (long items))
      (pos? (long blocks))
      (pos? (long recipes))))

(defn- run-init-pipeline!
  []
  (lifecycle/run-content-init!)
  (lifecycle/run-runtime-content-activation!)
  (lifecycle/run-datagen-metadata-init!))

(defn ensure-content-loaded!
  "Datagen runs outside normal mod init.
   We need content DSL registries populated (blocks/items/gui metadata)
   and content blockstate hooks installed, but this shared layer must not
   depend on concrete content namespaces at compile time.

   This function uses mcmod indirection to:
   - load discovered content bootstrap providers so they can register lifecycle init
   - run content init (installs hooks, binds mod-id, etc.)
   - activate runtime content (loads all DSL namespaces; fills registry metadata)
   - run content-owned datagen metadata hooks

   Called by both Forge and Fabric datagen entry points.
   Note: Uses cn.li.mcmod.config/*mod-id* for logging, so modid binding
   must be set up before calling this function."
  []
  (try
    (mc-content/register-all-content!)
    (run-init-pipeline!)
    (let [initial (snapshot-counts)]
      (when-not (populated? initial)
        (println (str "[" modid/*mod-id* "] WARNING: datagen metadata still empty after SPI bootstrap, "
                      "counts=" initial))))
    (catch Throwable t
      (println (str "[" modid/*mod-id* "] WARNING: failed to load content for datagen: "
                    (ex-message t)))
      (.printStackTrace t))))
