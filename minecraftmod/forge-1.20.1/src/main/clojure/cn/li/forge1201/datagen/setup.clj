(ns cn.li.forge1201.datagen.setup
  "Forge 1.20.1 DataGenerator Event Handler
   
   Registers all data generators for the mod.
   Triggered during setup phase when running:
     ./gradlew :forge-1.20.1:runData"
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mc1201.datagen.setup-common :as setup-common]
            [cn.li.forge1201.datagen.blockstate-provider :as bsp]
            [cn.li.forge1201.datagen.item-model-provider :as imp]
            [cn.li.forge1201.datagen.lang-provider :as lang]
            [cn.li.forge1201.datagen.advancement-provider :as adv]
            [cn.li.forge1201.datagen.recipe-provider :as rp])
  (:import [net.minecraftforge.data.event GatherDataEvent]
           [net.minecraft.data DataProvider$Factory DataGenerator]))



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
    ;; Ensure AC content registries are loaded before providers query metadata.
    (setup-common/ensure-ac-content-loaded!)
    
    ;; Register BlockState provider
    (println (str "[" modid/*mod-id* "] Registering BlockState DataGenerator..."))
    (add-provider! generator bsp/create exfile-helper)

    ;; Register Item Model provider
    (println (str "[" modid/*mod-id* "] Registering Item Model DataGenerator..."))
    (add-provider! generator imp/create exfile-helper)

    ;; Register Language provider
    (println (str "[" modid/*mod-id* "] Registering Lang DataGenerator..."))
    (add-provider! generator lang/create exfile-helper)

    ;; Register Recipe provider
    (println (str "[" modid/*mod-id* "] Registering Recipe DataGenerator..."))
    (add-provider! generator rp/create exfile-helper)

    ;; Register Advancement provider
    (println (str "[" modid/*mod-id* "] Registering Advancement DataGenerator..."))
    (add-provider! generator adv/create exfile-helper)
    
    (println (str "[" modid/*mod-id* "] DataGenerator setup complete!"))))

(defn static-gather-data
  "Static entry point used by Java annotation wrapper."
  [^GatherDataEvent event]
  (try
    (println (str "[" modid/*mod-id* "] Gathering data generators..."))
    (-gatherData event)
    (println (str "[" modid/*mod-id* "] DataGenerator event processed!"))
    (catch Exception e
      (println (str "Error handling GatherDataEvent: " e))
      (.printStackTrace e))))
