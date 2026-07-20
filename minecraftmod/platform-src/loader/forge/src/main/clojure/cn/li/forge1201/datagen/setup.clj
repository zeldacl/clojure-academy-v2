(ns cn.li.forge1201.datagen.setup
  "Forge 1.20.1 DataGenerator Event Handler
   
   Registers all data generators for the mod.
   Triggered during setup phase when running:
     ./gradlew :platform:runData -PplatformTarget=forge-1.20.1"
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.forge1201.datagen.provider-factory :as provider-factory]
            [cn.li.mc1201.datagen.provider-registration :as provider-registration]
            [cn.li.mc1201.datagen.setup-common :as setup-common])
  (:import [net.minecraftforge.data.event GatherDataEvent]))

(def ^:private providers
  [{:group :blockstate
    :id :blockstate
    :label "BlockState"
    :summary-label "blockstate"
    :factory :blockstate}
   {:group :item-model
    :id :item-model
    :label "Item Model"
    :summary-label "item-model"
    :factory :item-model}
   {:group :lang
    :id :lang
    :label "Lang"
    :summary-label "lang"
    :factory :lang}
   {:group :recipe
    :id :recipe
    :label "Recipe"
    :summary-label "recipe"
    :factory :recipe}
   {:group :advancement
    :id :advancement
    :label "Advancement"
    :summary-label "advancement"
    :factory :advancement}
   {:group :worldgen
    :id :worldgen
    :label "WorldGen"
    :summary-label "worldgen"
    :factory :worldgen}])

;; ============================================================================
;; EventBusSubscriber Configuration
;; ============================================================================

;; This metadata will be processed by Clojure's gen-class to configure the class
;; equivalent to: @Mod.EventBusSubscriber(modid = MyMod1201.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)

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
    ;; Ensure discovered content registries are loaded before providers query metadata.
    (setup-common/ensure-content-loaded!)

    (provider-registration/register-providers!
      {:mod-id modid/mod-id
       :target-label "forge-1.20.1"
       :providers providers
       :register-provider! (fn [provider]
                             (provider-factory/add-provider! generator exfile-helper provider))})))

(defn static-gather-data
  "Static entry point used by Java annotation wrapper."
  [^GatherDataEvent event]
  (try
    (println (str "[" modid/mod-id "] Gathering data generators..."))
    (-gatherData event)
    (println (str "[" modid/mod-id "] DataGenerator event processed!"))
    (catch Exception e
      (println (str "Error handling GatherDataEvent: " e))
      (.printStackTrace e))))
