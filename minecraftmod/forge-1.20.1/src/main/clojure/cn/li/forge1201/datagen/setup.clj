(ns cn.li.forge1201.datagen.setup
  "Forge 1.20.1 DataGenerator Event Handler
   
   Registers all data generators for the mod.
   Triggered during setup phase when running:
     ./gradlew :forge-1.20.1:runData"
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.forge1201.datagen.provider-factory :as provider-factory]
            [cn.li.mc1201.datagen.provider-registration :as provider-registration]
            [cn.li.mc1201.datagen.setup-common :as setup-common])
  (:import [net.minecraftforge.data.event GatherDataEvent]
           [net.minecraft.data DataGenerator]))

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
    (setup-common/ensure-content-loaded! "ac")

    (provider-registration/register-providers!
      :forge-1.20.1
      {:mod-id modid/*mod-id*
       :register-provider! (fn [provider]
                             (provider-factory/add-provider! generator exfile-helper provider))})))

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
