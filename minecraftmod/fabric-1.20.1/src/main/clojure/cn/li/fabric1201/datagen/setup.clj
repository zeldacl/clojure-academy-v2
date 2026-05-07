(ns cn.li.fabric1201.datagen.setup
  "Fabric 1.20.1 DataGenerator Setup

   Registers all data generators for JSON generation.

   Fabric uses different event system than Forge, so this module
   provides utilities to be called during data generation phase."
  (:require [cn.li.ac.config.modid :as modid]))

(defn register-data-generators!
  "Register all data generators for Fabric

   Call this during data generation phase."
  [_generator _exfile-helper]
  (println (str "[" modid/MOD-ID "] Fabric DataGenerator setup is currently no-op (providers are platform-specific).")))

(defn create-providers
  "Create all provider instances (for manual registration)"
  [_generator _exfile-helper]
  [])
