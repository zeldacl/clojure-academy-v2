(ns cn.li.fabric1201.datagen.setup
  "Fabric 1.20.1 DataGenerator Setup
   
   Registers all data generators for JSON generation.
   
   Fabric uses different event system than Forge, so this module
   provides utilities to be called during data generation phase."
  (:require [cn.li.ac.config.modid :as modid])
  (:import [net.minecraft.data DataGenerator DirectoryCache]))

;; ============================================================================
;; DataGenerator Utilities
;; ============================================================================

(defn register-data-generators!
  "Register all data generators for Fabric
   
   Call this during data generation phase.
   
   Args:
     generator: DataGenerator instance
     exfile-helper: ExistingFileHelper instance (optional)
   
   Usage:
     (register-data-generators! data-generator nil)"
  [^DataGenerator generator exfile-helper]
  
  (println (str "[" modid/MOD-ID "] Fabric DataGenerator setup is currently no-op (providers are platform-specific).")))

;; ============================================================================
;; For Direct Invocation (if needed)
;; ============================================================================

(defn create-providers
  "Create all provider instances (for manual registration)
   
   Returns: vector of [blockstate-provider, model-provider, item-provider]"
  [^DataGenerator generator exfile-helper]
  [])
