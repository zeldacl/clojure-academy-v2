(ns my-mod.datagen.model-provider
  "Forge DataGenerator for Block Model JSON files
   
   Generates block model references (parent model) with automatic mod-id handling."
  (:require [my-mod.config.modid :as modid]
            [clojure.data.json :as json])
  (:import [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraft.data DataGenerator DirectoryCache IDataProvider])
  (:gen-class
   :name my-mod.datagen.ModelProvider
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

(def BLOCK_MODELS
  "Map of block names to their model definitions
   
   Key: block registry name
   Value: map with :parent (optional), :textures, etc."
  {"matrix"
   {:parent "block/cube_all"
    :textures {:all (str modid/MOD-ID ":blocks/matrix")}}
   
   "windgen_main"
   {:parent "block/cube_all"
    :textures {:all (str modid/MOD-ID ":blocks/windgen_main")}}
   
   "windgen_pillar"
   {:parent "block/cube_all"
    :textures {:all (str modid/MOD-ID ":blocks/windgen_pillar")}}
   
   "windgen_base"
   {:parent "block/cube_all"
    :textures {:all (str modid/MOD-ID ":blocks/windgen_base")}}
   
   "windgen_fan"
   {:parent "block/cube_all"
    :textures {:all (str modid/MOD-ID ":blocks/windgen_fan")}}
   
   "solar_gen"
   {:parent "block/cube_all"
    :textures {:all (str modid/MOD-ID ":blocks/solar_gen")}}
   
   "phase_gen"
   {:parent "block/cube_all"
    :textures {:all (str modid/MOD-ID ":blocks/phase_gen")}}
   
   "reso_ore"
   {:parent "block/cube_all"
    :textures {:all (str modid/MOD-ID ":blocks/reso_ore")}}})

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
  "Main DataGenerator method - generates all block models
   
   Args:
     this: ModelProvider instance
     cache: DirectoryCache for tracking written files"
  [this ^DirectoryCache cache]
  (let [{:keys [generator exfileHelper]} (.state this)]
    (doseq [[block-name model-spec] BLOCK_MODELS]
      (generate-block-model! generator block-name model-spec))))

(defn -getName
  "Get provider name for logging"
  [this]
  "MyMod Block Models")

;; ============================================================================
;; Model Generation
;; ============================================================================

(defn generate-block-model!
  "Generate single block model JSON file
   
   Args:
     generator: DataGenerator instance
     block-name: String block registry name
     model-spec: Map with :parent, :textures, etc.
   
   Output path: assets/{modid}/models/block/{block-name}.json"
  [^DataGenerator generator block-name model-spec]
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
                      (resolve "block"))
        
        file-path (.resolve models-dir (str block-name ".json"))]
    
    ;; Create parent directories
    (java.nio.file.Files/createDirectories models-dir)
    
    ;; Write JSON file
    (spit (.toFile file-path) (json/write-str model-data))
    
    (println (str "Generated model: " (.relativize output-folder file-path)))))
