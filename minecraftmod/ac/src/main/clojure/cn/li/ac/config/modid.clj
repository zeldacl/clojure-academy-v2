(ns cn.li.ac.config.modid
  "Centralized mod-id configuration for easy portability across projects.
   Change MOD-ID here to update mod identification across all modules."
  (:require [cn.li.mcmod.config :as mcmod-config]))

(def ^:const MOD-ID
  "The primary mod identifier used across all resource locations, registries,
   and mod identification. Change this value to adapt the codebase for
   different mod projects."
  (or (System/getenv "MOD_ID") "my_mod"))

(defn install-modid!
  "Install AC's mod id into the shared mcmod config namespace.

  This is intentionally explicit: requiring this namespace must not mutate
  shared configuration state."
  []
  (alter-var-root #'mcmod-config/*mod-id* (constantly MOD-ID))
  nil)

(def resource-location mcmod-config/resource-location)
(def namespaced-path mcmod-config/namespaced-path)
(def asset-path mcmod-config/asset-path)
