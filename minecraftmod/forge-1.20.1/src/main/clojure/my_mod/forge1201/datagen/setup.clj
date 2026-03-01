(ns my-mod.forge1201.datagen.setup
  "Forge 1.20.1 DataGenerator Event Handler
   
   Registers all data generators for the mod.
   Triggered during setup phase when running:
     ./gradlew :forge-1.20.1:runData"
  (:require [my-mod.config.modid :as modid])
  (:import [net.minecraftforge.data.event GatherDataEvent]))

;; ============================================================================
;; EventBusSubscriber Configuration
;; ============================================================================

;; This metadata will be processed by Clojure's gen-class to configure the class
;; equivalent to: @Mod.EventBusSubscriber(modid = "my_mod", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)

;; ============================================================================
;; DataGenerator Event Handler
;; ============================================================================

(defn -gatherData
  "Event handler for GatherDataEvent
   
   Called by Forge when DataGenerator is ready to accept providers.
   
   Args:
     event: GatherDataEvent containing generator and file helper"
  [^GatherDataEvent event]
    (let [generator (.getGenerator event)
      exfile-helper (.getExistingFileHelper event)
      create-blockstate-provider (requiring-resolve 'my-mod.forge1201.datagen.blockstate-provider/->BlockStateProvider)
      create-model-provider (requiring-resolve 'my-mod.forge1201.datagen.model-provider/->ModelProvider)
      create-item-model-provider (requiring-resolve 'my-mod.forge1201.datagen.item-model-provider/->ItemModelProvider)]
    
    ;; Register BlockState provider
    (println (str "[" modid/MOD-ID "] Registering BlockState DataGenerator..."))
    (.addProvider generator
      (create-blockstate-provider generator exfile-helper))
    
    ;; Register Block Model provider
    (println (str "[" modid/MOD-ID "] Registering Block Model DataGenerator..."))
    (.addProvider generator
      (create-model-provider generator exfile-helper))
    
    ;; Register Item Model provider
    (println (str "[" modid/MOD-ID "] Registering Item Model DataGenerator..."))
    (.addProvider generator
      (create-item-model-provider generator exfile-helper))
    
    (println (str "[" modid/MOD-ID "] DataGenerator setup complete!"))))
