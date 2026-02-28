(ns my-mod.fabric1201.datagen.setup
  "Fabric 1.20.1 DataGenerator Setup
   
   Registers all data generators for JSON generation.
   
   Fabric uses different event system than Forge, so this module
   provides utilities to be called during data generation phase."
  (:require [my-mod.datagen.blockstate-provider :as bs-provider]
            [my-mod.datagen.model-provider :as model-provider]
            [my-mod.datagen.item-model-provider :as item-provider]
            [my-mod.config.modid :as modid])
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
  
  (println (str "[" modid/MOD-ID "] Registering Fabric DataGenerators..."))
  
  ;; Register BlockState provider
  (println (str "[" modid/MOD-ID "] Registering BlockState DataGenerator..."))
  (.addProvider generator
    (bs-provider/->BlockStateProvider generator exfile-helper))
  
  ;; Register Block Model provider
  (println (str "[" modid/MOD-ID "] Registering Block Model DataGenerator..."))
  (.addProvider generator
    (model-provider/->ModelProvider generator exfile-helper))
  
  ;; Register Item Model provider
  (println (str "[" modid/MOD-ID "] Registering Item Model DataGenerator..."))
  (.addProvider generator
    (item-provider/->ItemModelProvider generator exfile-helper))
  
  (println (str "[" modid/MOD-ID "] Fabric DataGenerator setup complete!")))

;; ============================================================================
;; For Direct Invocation (if needed)
;; ============================================================================

(defn create-providers
  "Create all provider instances (for manual registration)
   
   Returns: vector of [blockstate-provider, model-provider, item-provider]"
  [^DataGenerator generator exfile-helper]
  [(bs-provider/->BlockStateProvider generator exfile-helper)
   (model-provider/->ModelProvider generator exfile-helper)
   (item-provider/->ItemModelProvider generator exfile-helper)])
