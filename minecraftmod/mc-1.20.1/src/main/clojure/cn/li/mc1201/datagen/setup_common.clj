(ns cn.li.mc1201.datagen.setup-common
  "Shared datagen setup utilities, platform-independent.
   
   Provides common content initialization for both Forge and Fabric datagen phases."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.content :as mc-content]
            [cn.li.mcmod.lifecycle :as lifecycle]))

(defn ensure-ac-content-loaded!
  "Datagen runs outside normal mod init.
   We need gameplay DSL registries populated (blocks/items/gui metadata)
   and gameplay blockstate hooks installed, but platforms must not depend
   on content namespaces at compile time.

   This function uses mcmod indirection to:
   - load the content bootstrap provider so it can register lifecycle init
   - run content init (installs hooks, binds mod-id, etc.)
   - activate runtime content (loads all DSL namespaces; fills registry metadata)

   Called by both Forge and Fabric datagen entry points.
   Note: Uses cn.li.mcmod.config/*mod-id* for logging, so modid binding
   must be set up before calling this function."
  []
  (try
    (mc-content/ensure-content-init-registered!)
    (lifecycle/run-content-init!)
    (lifecycle/run-runtime-content-activation!)
    (catch Throwable t
      (println (str "[" modid/*mod-id* "] WARNING: failed to load gameplay content for datagen: "
                    (ex-message t))))))
