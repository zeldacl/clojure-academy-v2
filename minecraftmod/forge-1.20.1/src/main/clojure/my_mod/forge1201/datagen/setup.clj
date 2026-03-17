(ns my-mod.forge1201.datagen.setup
  "Forge 1.20.1 DataGenerator Event Handler
   
   Registers all data generators for the mod.
   Triggered during setup phase when running:
     ./gradlew :forge-1.20.1:runData"
  (:require [my-mod.config.modid :as modid]
            [my-mod.forge1201.datagen.blockstate-provider :as bsp]
            [my-mod.forge1201.datagen.item-model-provider :as imp]
            [my-mod.forge1201.datagen.lang-provider :as lang])
  (:import [net.minecraftforge.data.event GatherDataEvent]
           [net.minecraft.data DataProvider DataProvider$Factory DataGenerator]))

(defn- add-provider!
  "Registers a DataProvider via Factory, correctly using Minecraft 1.20.1 API.
   DataGenerator.addProvider expects a DataProvider.Factory<T> (PackOutput -> DataProvider)."
  [^DataGenerator generator create-fn exfile-helper]
  (.addProvider generator true
    (reify DataProvider$Factory
      (create [_ pack-output]
        (create-fn pack-output exfile-helper)))))

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
        exfile-helper (.getExistingFileHelper event)]
    
    ;; Register BlockState provider
    (println (str "[" modid/MOD-ID "] Registering BlockState DataGenerator..."))
    (add-provider! generator bsp/create exfile-helper)

    ;; Register Item Model provider
    (println (str "[" modid/MOD-ID "] Registering Item Model DataGenerator..."))
    (add-provider! generator imp/create exfile-helper)

    ;; Register Language provider
    (println (str "[" modid/MOD-ID "] Registering Lang DataGenerator..."))
    (add-provider! generator lang/create exfile-helper)
    
    (println (str "[" modid/MOD-ID "] DataGenerator setup complete!"))))
