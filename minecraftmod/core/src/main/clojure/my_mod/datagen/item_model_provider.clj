(ns my-mod.datagen.item-model-provider
  "Forge DataGenerator for Item Model JSON files
   
   Generates item model JSON files with parent references to block models
   or standard item textures."
  (:require [my-mod.config.modid :as modid]
            [clojure.data.json :as json])
  (:import [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraft.data DataGenerator DirectoryCache IDataProvider])
  (:gen-class
   :name my-mod.datagen.ItemModelProvider
   :extends Object
   :implements [net.minecraft.data.IDataProvider]
   :init init
   :state state
   :constructors {[net.minecraft.data.DataGenerator net.minecraftforge.common.data.ExistingFileHelper] []}
   :methods [[run [net.minecraft.data.DirectoryCache] void]
             [getName [] String]]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def ITEM_MODELS
  "Map of item names to their model definitions
   
   Items can have:
   - :block-variant: reference to corresponding block model
   - :parent + :textures: custom parent and texture specification"
  {"wafer"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/wafer")}}
   
   "tutorial"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/tutorial")}}
   
   "terminal_installer"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/terminal_installer")}}
   
   "silbarn"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/silbarn")}}
   
   "reso_crystal"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/reso_crystal")}}
   
   "resonance_component"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/resonance_component")}}
   
   "reinforced_iron_plate"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/reinforced_iron_plate")}}
   
   "needle"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/needle")}}
   
   "mat_core_0"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/mat_core_0")}}
   
   "mat_core_1"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/mat_core_1")}}
   
   "mat_core_2"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/mat_core_2")}}
   
   "media_0"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/media_sisters_noise")}}
   
   "media_1"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/media_only_my_railgun")}}
   
   "media_2"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/media_level5_judgelight")}}})

;; ============================================================================
;; Constructor
;; ============================================================================

(defn -init
  "Constructor: Store data generator and file helper in state"
  [generator exfileHelper]
  [[] {:generator generator
       :exfileHelper exfileHelper}])

;; ============================================================================
;; IDataProvider Implementation
;; ============================================================================

(defn -run
  "Main DataGenerator method - generates all item models
   
   Args:
     this: ItemModelProvider instance
     cache: DirectoryCache for tracking written files"
  [this ^DirectoryCache cache]
  (let [{:keys [generator exfileHelper]} (.state this)]
    (doseq [[item-name model-spec] ITEM_MODELS]
      (generate-item-model! generator item-name model-spec))))

(defn -getName
  "Get provider name for logging"
  [this]
  "MyMod Item Models")

;; ============================================================================
;; Model Generation
;; ============================================================================

(defn generate-item-model!
  "Generate single item model JSON file
   
   Args:
     generator: DataGenerator instance
     item-name: String item registry name
     model-spec: Map with :parent, :textures, etc.
   
   Output path: assets/{modid}/models/item/{item-name}.json"
  [^DataGenerator generator item-name model-spec]
  (let [model-data (-> model-spec
                       (update-keys (fn [k]
                                      (case k
                                        :parent "parent"
                                        :textures "textures"
                                        (name k))))
                       (update :textures (fn [textures]
                                           (reduce (fn [m [k v]]
                                                     (assoc m (name k) v))
                                                   {}
                                                   textures))))
        
        output-folder (.getOutputFolder generator)
        models-dir (.. output-folder
                      (resolve "assets")
                      (resolve modid/MOD-ID)
                      (resolve "models")
                      (resolve "item"))
        
        file-path (.resolve models-dir (str item-name ".json"))]
    
    ;; Create parent directories
    (java.nio.file.Files/createDirectories models-dir)
    
    ;; Write JSON file
    (spit (.toFile file-path) (json/write-str model-data))
    
    (println (str "Generated item model: " (.relativize output-folder file-path)))))
